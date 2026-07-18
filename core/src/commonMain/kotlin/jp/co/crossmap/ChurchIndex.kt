package jp.co.crossmap

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path
import org.gnit.lucenekmp.analysis.ja.JapaneseAnalyzer
import org.gnit.lucenekmp.analysis.en.EnglishAnalyzer
import org.gnit.lucenekmp.analysis.id.IndonesianAnalyzer
import org.gnit.lucenekmp.analysis.ko.KoreanAnalyzer
import org.gnit.lucenekmp.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.gnit.lucenekmp.analysis.pt.PortugueseAnalyzer
import org.gnit.lucenekmp.analysis.standard.StandardAnalyzer
import org.gnit.lucenekmp.document.Document
import org.gnit.lucenekmp.document.Field
import org.gnit.lucenekmp.document.LatLonDocValuesField
import org.gnit.lucenekmp.document.LatLonPoint
import org.gnit.lucenekmp.document.StringField
import org.gnit.lucenekmp.document.StoredField
import org.gnit.lucenekmp.document.TextField
import org.gnit.lucenekmp.index.IndexWriter
import org.gnit.lucenekmp.index.IndexWriterConfig
import org.gnit.lucenekmp.store.FSDirectory

object ChurchIndex {
    const val SCHEMA_VERSION = 7
    const val FIELD_ID = "id"
    const val FIELD_NAME = "name"
    const val FIELD_NAME_EXACT = "name_exact"
    const val FIELD_LOCALIZED_NAME = "localized_name"
    const val FIELD_NAME_JA = "name_ja"
    const val FIELD_NAME_KO = "name_ko"
    const val FIELD_NAME_EN = "name_en"
    const val FIELD_NAME_PT = "name_pt"
    const val FIELD_NAME_ID = "name_id"
    const val FIELD_NAME_OTHER = "name_other"
    const val FIELD_CATEGORY = "category"
    const val FIELD_DENOMINATION = "denomination"
    const val FIELD_ADDRESS = "address"
    const val FIELD_CONTENT = "content"
    const val FIELD_SOCIAL = "social"
    const val FIELD_GEONAME = "geoname"
    const val FIELD_TITLE_LANGUAGE = "title_language"
    const val FIELD_CONTENT_TYPE = "content_type"
    const val FIELD_LOCATION = "location"
    const val FIELD_RECORD = "record"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val localizedNameFields = listOf(
        FIELD_NAME_JA,
        FIELD_NAME_KO,
        FIELD_NAME_EN,
        FIELD_NAME_PT,
        FIELD_NAME_ID,
        FIELD_NAME_OTHER,
    )

    fun analyzer(languageCode: String = "ja") = PerFieldAnalyzerWrapper(
        languageAnalyzer(languageCode),
        mapOf(
            FIELD_NAME_JA to JapaneseAnalyzer(),
            FIELD_NAME_KO to KoreanAnalyzer(),
            FIELD_NAME_EN to EnglishAnalyzer(),
            FIELD_NAME_PT to PortugueseAnalyzer(),
            FIELD_NAME_ID to IndonesianAnalyzer(),
            FIELD_NAME_OTHER to StandardAnalyzer(),
        ),
    )

    private fun languageAnalyzer(languageCode: String) = when (languageCode.substringBefore('-').lowercase()) {
        "ja" -> JapaneseAnalyzer()
        "ko" -> KoreanAnalyzer()
        "en" -> EnglishAnalyzer()
        "pt" -> PortugueseAnalyzer()
        "id" -> IndonesianAnalyzer()
        else -> StandardAnalyzer()
    }

    fun localizedNameField(languageCode: String): String = when (languageCode.substringBefore('-').lowercase()) {
        "ja" -> FIELD_NAME_JA
        "ko" -> FIELD_NAME_KO
        "en" -> FIELD_NAME_EN
        "pt" -> FIELD_NAME_PT
        "id" -> FIELD_NAME_ID
        else -> FIELD_NAME_OTHER
    }

    fun normalizeExactName(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("""\s+"""), " ")

    fun build(
        indexPath: Path,
        churches: List<ChurchRecord>,
        languageCode: String = "ja",
        translatedGeoNames: Map<String, List<String>> = emptyMap(),
    ) {
        val directory = FSDirectory.open(indexPath)
        val normalizedLanguage = languageCode.substringBefore('-').lowercase()
        val config = IndexWriterConfig(analyzer(normalizedLanguage)).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE
        }
        IndexWriter(directory, config).use { writer ->
            churches.sortedBy { it.id }.forEach { church ->
                writer.addDocument(church.toDocument(normalizedLanguage, translatedGeoNames[church.id].orEmpty()))
            }
        }
        directory.close()
    }

    private fun ChurchRecord.toDocument(languageCode: String, translatedGeoNames: List<String>): Document = Document().apply {
        add(StringField(FIELD_ID, id, Field.Store.YES))
        titleLanguages.map { it.substringBefore('-').lowercase() }.filter(String::isNotBlank).distinct().forEach {
            add(StringField(FIELD_TITLE_LANGUAGE, it, Field.Store.NO))
        }
        val names = when (languageCode) {
            "ja" -> listOf(name) + localizedNames.filter { it.languageCode.substringBefore('-').lowercase() == "ja" }.map { it.name }
            "en" -> listOf(englishName) + localizedNames.filter { it.languageCode.substringBefore('-').lowercase() == "en" }.map { it.name }
            else -> localizedNames.filter { it.languageCode.substringBefore('-').lowercase() == languageCode }.map { it.name }
        }.filter(String::isNotBlank).distinct()
        names.forEach { localizedName ->
            add(StringField(FIELD_NAME_EXACT, normalizeExactName(localizedName), Field.Store.NO))
            add(TextField(FIELD_NAME, localizedName, Field.Store.YES))
            add(TextField(localizedNameField(languageCode), localizedName, Field.Store.NO))
            localizedName.lowercase().split(Regex("""\s+""")).filter(String::isNotBlank).forEach {
                add(StringField(FIELD_LOCALIZED_NAME, it, Field.Store.NO))
            }
        }
        translatedGeoNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase().replace(Regex("""\s+"""), " ") }
            .forEach {
            add(TextField(FIELD_GEONAME, it, Field.Store.NO))
        }
        localizedDenominationNames
            .filter { it.languageCode.substringBefore('-').lowercase() == languageCode }
            .map { it.name.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .forEach { add(TextField(FIELD_DENOMINATION, it, Field.Store.YES)) }
        if (languageCode == "ja") {
            category?.takeIf { it.isNotBlank() }?.let { add(TextField(FIELD_CATEGORY, it, Field.Store.YES)) }
            add(TextField(FIELD_ADDRESS, address, Field.Store.YES))
            val searchableContent = pages.joinToString("\n") { "${it.title}\n${it.text}" }
            if (searchableContent.isNotBlank()) add(TextField(FIELD_CONTENT, searchableContent, Field.Store.NO))
            pages.map { it.contentType.name }.distinct().forEach {
                add(StringField(FIELD_CONTENT_TYPE, it, Field.Store.NO))
            }
            val socialContent = socialProfiles.joinToString("\n") {
                listOfNotNull(it.handle, it.displayName, it.description).joinToString(" ")
            }
            if (socialContent.isNotBlank()) add(TextField(FIELD_SOCIAL, socialContent, Field.Store.NO))
        }
        add(LatLonPoint(FIELD_LOCATION, location.latitude, location.longitude))
        add(LatLonDocValuesField(FIELD_LOCATION, location.latitude, location.longitude))
        add(StoredField(FIELD_RECORD, json.encodeToString(this@toDocument)))
    }
}

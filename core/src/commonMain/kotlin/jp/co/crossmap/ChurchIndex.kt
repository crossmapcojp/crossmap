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
    const val SCHEMA_VERSION = 11
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
    const val FIELD_NAME_READING = "name_reading"
    const val FIELD_NAME_READING_EXACT = "name_reading_exact"
    const val FIELD_CATEGORY = "category"
    const val FIELD_CATEGORY_READING = "category_reading"
    const val FIELD_CATEGORY_READING_EXACT = "category_reading_exact"
    const val FIELD_DENOMINATION = "denomination"
    const val FIELD_DENOMINATION_READING = "denomination_reading"
    const val FIELD_DENOMINATION_READING_EXACT = "denomination_reading_exact"
    const val FIELD_ADDRESS = "address"
    const val FIELD_ADDRESS_GEONAME_CODE = "address_geoname_code"
    const val FIELD_ADDRESS_PREFECTURE = "address_prefecture"
    const val FIELD_ADDRESS_PREFECTURE_CODE = "address_prefecture_code"
    const val FIELD_ADDRESS_COUNTY = "address_county"
    const val FIELD_ADDRESS_MUNICIPALITY = "address_municipality"
    const val FIELD_ADDRESS_MUNICIPALITY_CODE = "address_municipality_code"
    const val FIELD_ADDRESS_CITY_WARD = "address_city_ward"
    const val FIELD_ADDRESS_CITY_WARD_CODE = "address_city_ward_code"
    const val FIELD_ADDRESS_KYOTO_STREET = "address_kyoto_street"
    const val FIELD_ADDRESS_LOCALITY = "address_locality"
    const val FIELD_ADDRESS_NUMBER = "address_number"
    const val FIELD_ADDRESS_BUILDING = "address_building"
    const val FIELD_CONTENT = "content"
    const val FIELD_SOCIAL = "social"
    const val FIELD_GEONAME = "geoname"
    const val FIELD_GEONAME_READING = "geoname_reading"
    const val FIELD_SEARCH_COMPACT = "search_compact"
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

    fun normalizeExactName(value: String): String {
        val normalized = value.trim().lowercase().replace(Regex("""\s+"""), " ")
        return if (normalized.any(::isJapaneseScript)) normalized.replace(" ", "") else normalized
    }

    private fun isJapaneseScript(value: Char): Boolean =
        value in '\u3040'..'\u30ff' || value in '\u3400'..'\u9fff'

    fun build(
        indexPath: Path,
        churches: List<ChurchRecord>,
        languageCode: String = "ja",
        translatedGeoNames: Map<String, List<String>> = emptyMap(),
        geonames: List<GeoName> = emptyList(),
        normalizedAddresses: Map<String, JapaneseAddress> = emptyMap(),
    ) {
        val directory = FSDirectory.open(indexPath)
        val normalizedLanguage = languageCode.substringBefore('-').lowercase()
        val config = IndexWriterConfig(analyzer(normalizedLanguage)).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE
        }
        IndexWriter(directory, config).use { writer ->
            churches.sortedBy { it.id }.forEach { church ->
                val normalizedAddress = normalizedAddresses[church.id]
                    ?: JapaneseAddressNormalizer.normalize(church.address, geonames)
                writer.addDocument(
                    church.toDocument(
                        normalizedLanguage,
                        translatedGeoNames[church.id].orEmpty(),
                        normalizedAddress,
                    )
                )
            }
        }
        directory.close()
    }

    private fun ChurchRecord.toDocument(
        languageCode: String,
        translatedGeoNames: List<String>,
        normalizedAddress: JapaneseAddress,
    ): Document = Document().apply {
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
        val cleanTranslatedGeoNames = translatedGeoNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase().replace(Regex("""\s+"""), " ") }
        cleanTranslatedGeoNames.forEach { add(TextField(FIELD_GEONAME, it, Field.Store.NO)) }
        val denominationNames = localizedDenominationNames
            .filter { it.languageCode.substringBefore('-').lowercase() == languageCode }
            .map { it.name.trim() }
            .filter(String::isNotBlank)
            .distinct()
        denominationNames.forEach { add(TextField(FIELD_DENOMINATION, it, Field.Store.YES)) }
        val nameReadings = if (languageCode == "ja") readingVariants(names) else emptyList()
        val denominationReadings = if (languageCode == "ja") readingVariants(denominationNames) else emptyList()
        val categoryReadings = if (languageCode == "ja") {
            readingVariants(listOfNotNull(category?.takeIf(String::isNotBlank)))
        } else {
            emptyList()
        }
        val japaneseGeoNames = if (languageCode == "ja") {
            (
                cleanTranslatedGeoNames + listOfNotNull(
                    normalizedAddress.prefecture,
                    normalizedAddress.county,
                    normalizedAddress.municipality,
                    normalizedAddress.cityWard,
                    normalizedAddress.locality,
                )
                ).distinct()
        } else {
            emptyList()
        }
        val geonameReadings = readingVariants(japaneseGeoNames)
        nameReadings.forEach { add(TextField(FIELD_NAME_READING, it, Field.Store.NO)) }
        denominationReadings.forEach { add(TextField(FIELD_DENOMINATION_READING, it, Field.Store.NO)) }
        categoryReadings.forEach { add(TextField(FIELD_CATEGORY_READING, it, Field.Store.NO)) }
        geonameReadings.forEach { add(TextField(FIELD_GEONAME_READING, it, Field.Store.NO)) }
        nameReadings.map(::compactReading).distinct().forEach {
            add(StringField(FIELD_NAME_READING_EXACT, it, Field.Store.NO))
        }
        denominationReadings.map(::compactReading).distinct().forEach {
            add(StringField(FIELD_DENOMINATION_READING_EXACT, it, Field.Store.NO))
        }
        categoryReadings.map(::compactReading).distinct().forEach {
            add(StringField(FIELD_CATEGORY_READING_EXACT, it, Field.Store.NO))
        }
        val compactSearchText = buildList {
            addAll(names)
            addAll(cleanTranslatedGeoNames)
            addAll(denominationNames)
            addAll(nameReadings)
            addAll(denominationReadings)
            addAll(categoryReadings)
            addAll(geonameReadings)
            if (languageCode == "ja") {
                category?.takeIf(String::isNotBlank)?.let(::add)
                address.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().joinToString("\n")
        if (compactSearchText.isNotBlank()) add(TextField(FIELD_SEARCH_COMPACT, compactSearchText, Field.Store.NO))
        normalizedAddress.prefecture?.let { add(StringField(FIELD_ADDRESS_PREFECTURE, it, Field.Store.YES)) }
        normalizedAddress.prefectureCode?.let {
            add(StringField(FIELD_ADDRESS_PREFECTURE_CODE, it, Field.Store.YES))
            add(StringField(FIELD_ADDRESS_GEONAME_CODE, it, Field.Store.NO))
        }
        normalizedAddress.county?.let { add(StringField(FIELD_ADDRESS_COUNTY, it, Field.Store.YES)) }
        normalizedAddress.municipality?.let { add(StringField(FIELD_ADDRESS_MUNICIPALITY, it, Field.Store.YES)) }
        normalizedAddress.municipalityCode?.let {
            add(StringField(FIELD_ADDRESS_MUNICIPALITY_CODE, it, Field.Store.YES))
            add(StringField(FIELD_ADDRESS_GEONAME_CODE, it, Field.Store.NO))
        }
        normalizedAddress.cityWard?.let { add(StringField(FIELD_ADDRESS_CITY_WARD, it, Field.Store.YES)) }
        normalizedAddress.cityWardCode?.let {
            add(StringField(FIELD_ADDRESS_CITY_WARD_CODE, it, Field.Store.YES))
            add(StringField(FIELD_ADDRESS_GEONAME_CODE, it, Field.Store.NO))
        }
        normalizedAddress.kyotoStreet?.let { add(StringField(FIELD_ADDRESS_KYOTO_STREET, it, Field.Store.YES)) }
        normalizedAddress.locality?.let { add(StringField(FIELD_ADDRESS_LOCALITY, it, Field.Store.YES)) }
        normalizedAddress.addressNumber?.let { add(StringField(FIELD_ADDRESS_NUMBER, it, Field.Store.YES)) }
        normalizedAddress.building?.let { add(StringField(FIELD_ADDRESS_BUILDING, it, Field.Store.YES)) }
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

    private fun readingVariants(values: List<String>): List<String> = values.asSequence()
        .flatMap { JapaneseReadingNormalizer.searchReadings(it).asSequence() }
        .filter(String::isNotBlank)
        .flatMap { reading -> sequenceOf(reading, reading.replace(" ", "")) }
        .distinct()
        .toList()

    private fun compactReading(value: String): String = value.replace(" ", "")
}

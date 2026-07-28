package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.Language
import jp.co.crossmap.ChurchTradition
import jp.co.crossmap.DenominationNameEvidence
import jp.co.crossmap.DenominationNameMethod
import jp.co.crossmap.DenominationNames
import jp.co.crossmap.LocalizedText
import jp.co.crossmap.denominationNamePart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Loads the committed per-language denomination-name catalogs. */
object DenominationNameCatalogFiles {
    private val json = Json { ignoreUnknownKeys = true }

    fun path(resourcesRoot: Path, language: Language): Path =
        resourcesRoot.resolve("catalog/denomination-${language.code}-names.json")

    fun load(resourcesRoot: Path): Map<Language, Map<String, String>> {
        val japanese = json.decodeFromString<Map<String, String>>(Files.readString(path(resourcesRoot, Language.JAPANESE)))
        return Language.entries.associateWith { language ->
            val catalogPath = path(resourcesRoot, language)
            if (Files.isRegularFile(catalogPath)) {
                json.decodeFromString<Map<String, String>>(Files.readString(catalogPath))
            } else {
                japanese
            }
                .also { names ->
                    require(names.values.none(String::isBlank)) {
                        "Blank denomination name in $catalogPath"
                    }
                }
        }
    }

    fun metadataPath(resourcesRoot: Path): Path =
        resourcesRoot.resolve("catalog/denomination-name-metadata.json")

    fun loadReviewed(resourcesRoot: Path): Map<String, DenominationNames> {
        val names = load(resourcesRoot)
        val metadata = json.decodeFromString<Map<String, DenominationNameMetadata>>(
            Files.readString(metadataPath(resourcesRoot)),
        )
        val expectedIds = names.getValue(Language.JAPANESE).keys
        require(metadata.keys == expectedIds) { "Denomination metadata IDs must exactly match name catalogs" }
        return expectedIds.associateWith { id ->
            val localizedNames = Language.entries.associateWith { language -> names.getValue(language).getValue(id) }
            val localizedParts = localizedNames.mapValues { (language, value) -> denominationNamePart(value, language) }
            val record = metadata.getValue(id)
            val evidence = Language.entries.associateWith { language ->
                val item = requireNotNull(
                    record.evidence[language.code]
                        ?: record.evidence[Language.ENGLISH.code]
                        ?: record.evidence[Language.JAPANESE.code],
                ) {
                    "$id is missing ${language.code} provenance and Japanese fallback provenance"
                }
                DenominationNameEvidence(
                    method = DenominationNameMethod.valueOf(item.method),
                    sourceUrl = item.sourceUrl,
                    note = item.note,
                )
            }
            DenominationNames(
                id = id,
                tradition = record.tradition?.let(ChurchTradition::valueOf),
                names = localizedNames.toLocalizedText(),
                nameParts = localizedParts.toLocalizedText(),
                evidence = evidence,
            )
        }
    }

    private fun Map<Language, String>.toLocalizedText(): LocalizedText = LocalizedText.of(
        japanese = getValue(Language.JAPANESE),
        english = getValue(Language.ENGLISH),
        korean = getValue(Language.KOREAN),
        portuguese = getValue(Language.PORTUGUESE),
        indonesian = getValue(Language.INDONESIAN),
        chineseSimplified = getValue(Language.CHINESE_SIMPLIFIED),
        chineseTraditional = getValue(Language.CHINESE_TRADITIONAL),
        vietnamese = getValue(Language.VIETNAMESE),
    )
}

@Serializable
private data class DenominationNameMetadata(
    val tradition: String? = null,
    val evidence: Map<String, DenominationEvidenceFile>,
)

@Serializable
private data class DenominationEvidenceFile(
    val method: String,
    val sourceUrl: String? = null,
    val note: String? = null,
)

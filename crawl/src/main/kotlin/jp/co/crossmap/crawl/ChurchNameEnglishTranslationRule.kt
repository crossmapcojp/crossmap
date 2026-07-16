package jp.co.crossmap.crawl

import java.net.URI

interface ChurchNameEnglishTranslationRule {
    fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName?
}

class StructuredChurchNameRule(
    denominations: List<Denomination>,
    geonames: Map<String, String> = ChurchNameEnglishLexicon.geonames,
    concepts: Map<String, String> = emptyMap(),
) : ChurchNameEnglishTranslationRule {
    private val analyzer = ChurchNameComponentAnalyzer(denominations, geonames, concepts = concepts)
    private val denominationAbbreviations = denominations.flatMap { denomination ->
        (listOf(denomination.name) + denomination.aliases).map { alias -> alias to denomination.id }
    }.associate { it }.mapValues { (_, id) ->
        id.takeIf { it.matches(Regex("""[A-Z][A-Z0-9]{1,9}""")) }
    }

    override fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        val analysis = analyzer.analyze(church) ?: return null
        val localEnglishName = analysis.compose() ?: return null
        val denominationPrefix = analysis.denominationAlias?.let(denominationAbbreviations::get)
        val englishName = listOfNotNull(denominationPrefix, localEnglishName).joinToString(" ")
        return ProgrammaticEnglishName(
            englishName,
            0.99f,
            "Deterministic structured denomination/name-part/congregation translation",
        )
    }
}

class GenericChurchNameFromAddressRule(
    denominations: List<Denomination>,
    private val geonames: Map<String, String>,
) : ChurchNameEnglishTranslationRule {
    private val denominationIds = denominations.associateBy(Denomination::id).keys

    override fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        val congregation = when (church.name.replace(" ", "")) {
            "教会", "キリスト教会" -> "Church"
            "チャペル" -> "Chapel"
            "キリスト集会" -> "Christian Assembly"
            else -> return null
        }
        val geoname = geonames.entries
            .asSequence()
            .filter { (japanese, english) ->
                japanese.length >= 2 && english.isNotBlank() && church.address.contains(japanese)
            }
            .maxWithOrNull(
                compareBy<Map.Entry<String, String>> { church.address.lastIndexOf(it.key) }
                    .thenBy { it.key.length },
            )
            ?.value
            ?: return null
        val denomination = church.denominationId
            ?.takeIf { it in denominationIds && it.matches(Regex("""[A-Z][A-Z0-9]{1,9}""")) }
        return ProgrammaticEnglishName(
            englishName = listOfNotNull(denomination, geoname, congregation).joinToString(" "),
            confidence = 0.96f,
            evidence = "Generic church title completed from denomination and most-specific address geoname",
        )
    }
}

class GeonameChristianAssemblyNameRule(
    private val geonames: Map<String, String>,
    private val romanize: (String) -> String? = JapaneseNameRomanizer::romanize,
) : ChurchNameEnglishTranslationRule {
    override fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        val geoname = church.name.removeSuffix("キリスト集会").takeIf { it != church.name } ?: return null
        val english = geonames[geoname]
            ?: geoname.takeIf(church.address::contains)?.let(romanize)
            ?: return null
        return ProgrammaticEnglishName(
            "$english Christian Assembly",
            1f,
            "Geoname + Christian assembly rule",
        )
    }
}

class GeonameTraditionChurchNameRule(
    private val geonames: Map<String, String>,
    private val traditions: Map<String, String>,
    private val romanize: (String) -> String? = JapaneseNameRomanizer::romanize,
) : ChurchNameEnglishTranslationRule {
    override fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        val stem = church.name.removeSuffix("教会").takeIf { it != church.name } ?: return null
        val traditionJapanese = traditions.keys.sortedByDescending(String::length).firstOrNull(stem::endsWith) ?: return null
        val traditionEnglish = traditions.getValue(traditionJapanese)
        val geoname = stem.removeSuffix(traditionJapanese).takeIf(String::isNotBlank) ?: return null
        val geonameEnglish = geonames[geoname]
            ?: geoname.takeIf(church.address::contains)?.let(romanize)
            ?: return null
        return ProgrammaticEnglishName(
            "$geonameEnglish $traditionEnglish Church",
            1f,
            "Geoname + tradition + church rule",
        )
    }
}

class DenominationAliasGeonameChurchNameRule(
    denominations: List<Denomination>,
    private val geonames: Map<String, String>,
    private val romanize: (String) -> String? = JapaneseNameRomanizer::romanize,
) : ChurchNameEnglishTranslationRule {
    private val aliases = denominations.flatMap { denomination ->
        (listOf(denomination.name) + denomination.aliases).filter(String::isNotBlank)
            .map { alias -> alias to denomination.id }
    }.distinctBy { it.first }.sortedByDescending { it.first.length }

    override fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        val eligibleAliases = church.denominationId
            ?.takeUnless { it == NOT_DETERMINED }
            ?.let { knownId -> aliases.filter { (_, denominationId) -> denominationId == knownId } }
            ?: aliases
        val (alias, denominationId) = eligibleAliases.firstOrNull { church.name.startsWith(it.first) } ?: return null
        if (!church.denominationId.isNullOrBlank() && church.denominationId != NOT_DETERMINED &&
            church.denominationId != denominationId
        ) return null
        val remainder = church.name.removePrefix(alias).removeSuffix("教会").trim()
        val geonameEnglish = geonames[remainder]
            ?: remainder.takeIf(church.address::contains)?.let(romanize)
            ?: translateKnownGeonameAndKanaProperName(remainder)
            ?: remainder.takeIf(::isKanaProperName)?.let(romanize)
            ?: return null
        return ProgrammaticEnglishName(
            "$geonameEnglish Church",
            1f,
            "Denomination name/alias + romanized proper-name stem + church rule ($alias)",
        )
    }

    private fun isKanaProperName(value: String): Boolean =
        value.any { it in '\u3040'..'\u30ff' } && value.all {
            it.isWhitespace() || it == '・' || it == 'ー' || it == '-' || it in '\u3040'..'\u30ff'
        }

    private fun translateKnownGeonameAndKanaProperName(value: String): String? {
        val (japanese, english) = geonames.entries.sortedByDescending { it.key.length }
            .firstOrNull { value.startsWith(it.key) && value.length > it.key.length }
            ?: return null
        val properName = value.removePrefix(japanese).trim()
        if (!isKanaProperName(properName)) return null
        return "$english ${romanize(properName) ?: return null}"
    }
}

/** Stable fallback for Japanese proper names when no URL token needs LLM reconstruction. */
class RomanizedJapaneseChurchNameRule(
    denominations: List<Denomination>,
    private val romanize: (String) -> String? = JapaneseNameRomanizer::romanize,
) : ChurchNameEnglishTranslationRule {
    private val denominationAliases = denominations.flatMap { listOf(it.name) + it.aliases }
        .filter(String::isNotBlank)
        .sortedByDescending(String::length)
    private val suffixes = listOf(
        "キリスト集会" to "Christian Assembly",
        "大聖堂" to "Cathedral",
        "チャペル" to "Chapel",
        "礼拝堂" to "Chapel",
        "会堂" to "Chapel",
        "伝道所" to "Mission",
        "ミッション" to "Mission",
        "チャーチ" to "Church",
        "教会" to "Church",
        "聖堂" to "Chapel",
        "小隊" to "Mission",
    )

    override fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName? {
        if (authoritativeUrlProperName(church.websiteUrl) != null) return null
        val (suffix, congregation) = suffixes.firstOrNull { church.name.endsWith(it.first) } ?: return null
        var stem = church.name.removeSuffix(suffix).trim()
        denominationAliases.firstOrNull(stem::startsWith)?.let { stem = stem.removePrefix(it).trim() }
        val english = romanize(stem) ?: return null
        return ProgrammaticEnglishName(
            "$english $congregation",
            0.96f,
            "Deterministic Kuromoji reading romanization + congregation rule",
        )
    }

    private fun authoritativeUrlProperName(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        if (host.isBlank()) return null
        if (GENERIC_HOSTS.any { host == it || host.endsWith(".$it") }) return null
        return host
    }

    private companion object {
        val GENERIC_HOSTS = setOf(
            "google.com", "facebook.com", "instagram.com", "youtube.com", "x.com", "twitter.com",
        )
    }
}

object ChurchNameEnglishTranslationRules {
    private val geonames = ChurchNameEnglishLexicon.geonames
    private val traditions = ChurchNameEnglishLexicon.traditions
    private val builtInDenominations = listOf(
        Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団")),
        Denomination("JELC", "日本福音ルーテル教会", listOf("日本福音ルーテル")),
        Denomination("JHC", "日本ホーリネス教団"),
        Denomination("ANGLICAN_JP", "日本聖公会", listOf("NSKK")),
        Denomination("HPBC", "Hawaii Pacific Baptist Convention", listOf("HPBC")),
    )

    fun create(
        denominations: List<Denomination> = emptyList(),
        additionalGeonames: Map<String, String> = emptyMap(),
        additionalConcepts: Map<String, String> = emptyMap(),
    ): List<ChurchNameEnglishTranslationRule> {
        val completeDenominations = (denominations + builtInDenominations).distinctBy(Denomination::id)
        val completeGeonames = geonames + additionalGeonames
        val completeConcepts = additionalConcepts
        return listOf(
            GenericChurchNameFromAddressRule(completeDenominations, completeGeonames),
            StructuredChurchNameRule(completeDenominations, completeGeonames, completeConcepts),
            GeonameChristianAssemblyNameRule(completeGeonames),
            DenominationAliasGeonameChurchNameRule(completeDenominations, completeGeonames),
            GeonameTraditionChurchNameRule(completeGeonames, traditions),
            RomanizedJapaneseChurchNameRule(completeDenominations),
        )
    }
}

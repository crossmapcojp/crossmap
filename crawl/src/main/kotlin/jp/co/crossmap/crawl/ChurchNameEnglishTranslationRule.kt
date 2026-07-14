package jp.co.crossmap.crawl

import java.net.URI

interface ChurchNameEnglishTranslationRule {
    fun translate(church: ChurchEnglishNameInput): ProgrammaticEnglishName?
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
        val (alias, denominationId) = aliases.firstOrNull { church.name.startsWith(it.first) } ?: return null
        if (!church.denominationId.isNullOrBlank() && church.denominationId != NOT_DETERMINED &&
            church.denominationId != denominationId
        ) return null
        val remainder = church.name.removePrefix(alias).removeSuffix("教会")
        val geonameEnglish = geonames[remainder]
            ?: remainder.takeIf(church.address::contains)?.let(romanize)
            ?: return null
        return ProgrammaticEnglishName(
            "$geonameEnglish Church",
            1f,
            "Denomination name/alias + geoname + church rule ($alias)",
        )
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
    private val geonames = linkedMapOf(
        "東京" to "Tokyo", "川崎" to "Kawasaki", "赤羽" to "Akabane", "大阪" to "Osaka",
        "姫路" to "Himeji", "横浜" to "Yokohama", "京都" to "Kyoto", "神戸" to "Kobe",
        "名古屋" to "Nagoya", "札幌" to "Sapporo", "福岡" to "Fukuoka", "仙台" to "Sendai",
        "千葉" to "Chiba", "広島" to "Hiroshima", "岡山" to "Okayama", "奈良" to "Nara",
        "経堂" to "Kyodo",
    )
    private val traditions = mapOf(
        "バプテスト" to "Baptist",
        "ホーリネス" to "Holiness",
        "ルーテル" to "Lutheran",
        "長老" to "Presbyterian",
        "福音" to "Gospel",
        "聖公会" to "Anglican",
        "カトリック" to "Catholic",
    )
    private val builtInDenominations = listOf(
        Denomination("UCCJ", "日本基督教団", listOf("日本キリスト教団")),
        Denomination("JELC", "日本福音ルーテル教会", listOf("日本福音ルーテル")),
        Denomination("JHC", "日本ホーリネス教団"),
        Denomination("HPBC", "Hawaii Pacific Baptist Convention", listOf("HPBC")),
    )

    fun create(denominations: List<Denomination> = emptyList()): List<ChurchNameEnglishTranslationRule> {
        val completeDenominations = (denominations + builtInDenominations).distinctBy(Denomination::id)
        return listOf(
            GeonameChristianAssemblyNameRule(geonames),
            DenominationAliasGeonameChurchNameRule(completeDenominations, geonames),
            GeonameTraditionChurchNameRule(geonames, traditions),
            RomanizedJapaneseChurchNameRule(completeDenominations),
        )
    }
}

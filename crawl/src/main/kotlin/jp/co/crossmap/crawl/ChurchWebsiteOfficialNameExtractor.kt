package jp.co.crossmap.crawl

import java.net.URI
import jp.co.crossmap.Language
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.LocalizedNameGenerationMethod
import jp.co.crossmap.LocalizedNameMetadata
import jp.co.crossmap.LocalizedNameReviewStatus
import jp.co.crossmap.LocalizedNameSource
import org.jsoup.Jsoup

/** Extracts an official localized church name from a church-owned HTML page. */
object ChurchWebsiteOfficialNameExtractor {
    data class Result(val localizedName: LocalizedName, val pageLanguageCode: String)

    fun extract(pageUrl: String, html: String): Result? {
        if (html.isBlank()) return null
        val document = Jsoup.parse(html, pageUrl)
        val heading = document.selectFirst("main h1, article h1, h1")
            ?.text()
            ?.replace(whitespace, " ")
            ?.trim()
            ?.takeIf { it.length in 3..160 && churchNameMarker.containsMatchIn(it) }
            ?: return null
        val language = languageFor(pageUrl, document.selectFirst("html")?.attr("lang"), heading)
        val officialName = if (language == Language.ENGLISH) appendSiteAcronym(heading, document) else heading
        val languageCode = language.code
        return Result(
            localizedName = LocalizedName(
                languageCode = languageCode,
                name = officialName,
                metadata = LocalizedNameMetadata(
                    source = LocalizedNameSource.OFFICIAL,
                    generationMethod = LocalizedNameGenerationMethod.EXACT_OVERRIDE,
                    confidence = 1.0,
                    reviewStatus = LocalizedNameReviewStatus.UNREVIEWED,
                    reviewReasons = listOf("Extracted from the primary heading of the official church website"),
                ),
            ),
            pageLanguageCode = languageCode,
        )
    }

    private fun languageFor(pageUrl: String, htmlLanguage: String?, heading: String): Language {
        val firstPathSegment = runCatching { URI(pageUrl).path.orEmpty().trim('/').substringBefore('/') }
            .getOrDefault("")
            .lowercase()
        return when (firstPathSegment) {
            "j", "ja", "jp", "japanese" -> Language.JAPANESE
            "c", "cn", "zh", "zh-cn", "zh-hans", "chinese" -> Language.CHINESE_SIMPLIFIED
            "tw", "zh-tw", "zh-hk", "zh-hant", "traditional" -> Language.CHINESE_TRADITIONAL
            else -> Language.fromCode(htmlLanguage) ?: inferLanguage(heading)
        }
    }

    private fun inferLanguage(value: String): Language = when {
        japaneseMarker.containsMatchIn(value) -> Language.JAPANESE
        hanMarker.containsMatchIn(value) -> Language.CHINESE_SIMPLIFIED
        else -> Language.ENGLISH
    }

    private fun appendSiteAcronym(heading: String, document: org.jsoup.nodes.Document): String {
        val acronym = document.selectFirst("meta[property=og:site_name], meta[name=application-name]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { acronymPattern.matches(it) && !heading.contains("($it)", ignoreCase = true) }
            ?: return heading
        return "$heading ($acronym)"
    }

    private val whitespace = Regex("\\s+")
    private val acronymPattern = Regex("[A-Z][A-Z0-9]{1,9}")
    private val churchNameMarker = Regex(
        "church|chapel|congregation|教会|教會|聖堂|チャーチ|基督教|キリスト教",
        RegexOption.IGNORE_CASE,
    )
    private val japaneseMarker = Regex("[ぁ-ゟ゠-ヿ]|教会")
    private val hanMarker = Regex("[一-龯]|教會")
}

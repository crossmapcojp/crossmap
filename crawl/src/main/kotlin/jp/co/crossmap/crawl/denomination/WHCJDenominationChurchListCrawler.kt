package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.SocialProfile
import jp.co.crossmap.crawl.SocialUrlNormalizer
import org.jsoup.Jsoup

class WHCJDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "WHCJ"
    override val denominationName = "ウェスレアン・ホーリネス教団"
    override val sourceUrl = "resource:crawl/whcj-churches.html"
    override val outputFileName = "whcj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        var jurisdiction = ""
        val churches = mutableListOf<OfficialDenominationChurch>()
        Jsoup.parse(html, "https://whchurch.jimdofree.com/").select("tr").forEach { row ->
            val text = row.text().replace(Regex("\\s+"), " ").trim()
            val normalizedHeader = text.replace(Regex("\\s*[・･]\\s*"), "・")
            if (normalizedHeader in jurisdictionNames) {
                jurisdiction = normalizedHeader
                return@forEach
            }
            if (normalizedHeader in terminalSections) {
                jurisdiction = ""
                return@forEach
            }
            if (jurisdiction.isBlank() || text.isBlank() || text.contains("在韓") || text.contains("ハングル")) return@forEach

            val links = row.select("a[href]").map { link ->
                link.text().trim() to originalUrl(link.absUrl("href"))
            }.filter { (_, url) -> url.startsWith("http") }
            val churchLink = links.firstOrNull { (label) -> looksLikeChurchName(label) }
            val name = churchLink?.first ?: text
                .replace(Regex("\\s+(?:フェイス[・･]?ブック|Facebook|ブログ).*$", RegexOption.IGNORE_CASE), "")
                .trim()
            if (!looksLikeChurchName(name)) return@forEach
            val socialProfiles = links.mapNotNull { (_, url) ->
                val platform = SocialUrlNormalizer.platform(url) ?: return@mapNotNull null
                SocialProfile(platform, SocialUrlNormalizer.canonical(url, platform), SocialUrlNormalizer.handle(url))
            }.distinctBy { it.platform to it.url }
            val website = links.firstOrNull { (_, url) -> SocialUrlNormalizer.platform(url) == null }?.second.orEmpty()
            churches += OfficialDenominationChurch(
                name = name,
                jurisdiction = jurisdiction,
                websiteUrl = website,
                socialProfiles = socialProfiles,
            )
        }
        return churches
    }

    private fun originalUrl(url: String): String =
        Regex("^https?://web\\.archive\\.org/web/[^/]+/(https?://.+)$").matchEntire(url)?.groupValues?.get(1) ?: url

    private fun looksLikeChurchName(value: String): Boolean =
        value.isNotBlank() && churchNameMarkers.any(value::contains) &&
            value !in jurisdictionNames && value !in terminalSections

    private companion object {
        val jurisdictionNames = setOf(
            "北海道教区", "東北教区", "関東教区", "千葉・東京東教区", "神奈川・東京南教区",
            "静岡教区", "信越教区", "名阪教区", "九州・沖縄教区",
        )
        val terminalSections = setOf("海外宣教", "関係教会")
        val churchNameMarkers = listOf("教会", "チャペル", "チャーチ", "キリスト", "クリスチャン")
    }
}

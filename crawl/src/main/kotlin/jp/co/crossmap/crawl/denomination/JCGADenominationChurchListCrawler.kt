package jp.co.crossmap.crawl.denomination

import java.text.Normalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class JCGADenominationChurchListCrawler(
    override val sourceUrl: String,
) : SinglePageDenominationChurchListCrawler {
    override val denominationId = "JCGA"
    override val denominationName = "日本チャーチオブゴッド教団"
    override val outputFileName = "jcga-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> =
        Jsoup.parse(html, sourceUrl).select("div[data-testid=richTextElement]").mapNotNull { block ->
            val name = churchName(block)
            if (name.isBlank()) return@mapNotNull null

            val text = normalize(block.text())
            val address = addressPattern.find(text)?.groupValues?.get(1)
                ?.let(DirectoryCrawlerSupport::normalizeAddress)
                .orEmpty()
            val links = block.select("a[href]")
            OfficialDenominationChurch(
                name = name,
                address = address,
                jurisdiction = prefecturePattern.find(address)?.value.orEmpty(),
                phone = DirectoryCrawlerSupport.phoneFromText(text),
                websiteUrl = DirectoryCrawlerSupport.externalWebsite(links, "www.japanchurchofgod.org"),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(links),
                ministers = ChurchMinisterParser.parse(text),
            )
        }.distinctBy(OfficialDenominationChurch::name)

    private fun churchName(block: Element): String {
        val value = block.selectFirst("h1")?.text()?.let(::normalize).orEmpty()
        return value.takeIf { churchNamePattern.matches(it) }.orEmpty()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(zeroWidth, "")
        .replace(whitespace, " ")
        .trim()

    private companion object {
        val zeroWidth = Regex("""[\u200B-\u200D\uFEFF]""")
        val whitespace = Regex("""\s+""")
        val churchNamePattern = Regex("""(?:.+(?:教会|チャーチ|チャペル|ハウス)|CSLR MOJ|J\s*-\s*Center)""")
        val prefecturePattern = Regex("""北海道|東京都|京都府|大阪府|[一-龯]{2,3}県""")
        val addressPattern = Regex(
            """((?:北海道|東京都|京都府|大阪府|[一-龯]{2,3}県).+?)(?=\s*(?:TEL|牧師)[：:]?)""",
        )
    }
}

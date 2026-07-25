package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.crawl.DenominationDirectorySource

interface CatholicDioceseChurchListCrawler : MultiPageDenominationChurchListCrawler {
    val dioceseSlug: String
    val jurisdictionNames: Set<String>

    override val denominationId: String
        get() = "CATHOLIC_JP"
    override val denominationName: String
        get() = "カトリック中央協議会"
    override val sourceUrl: String
        get() = "https://www.cbcj.catholic.jp/japan/diocese/$dioceseSlug/"
    override val outputFileName: String
        get() = "catholic_jp-$dioceseSlug-churches.json"
}

class CatholicJpDenominationChurchListCrawler(
    source: DenominationDirectorySource,
) : MultiPageDenominationChurchListCrawler {
    private val dioceseCrawlers = listOf(
        CatholicSapporoDioceseChurchListCrawler(source.urlsFor("札幌教区")),
        CatholicSendaiDioceseChurchListCrawler(source.urlsFor("仙台教区")),
        CatholicNiigataDioceseChurchListCrawler(source.urlsFor("新潟教区")),
        CatholicSaitamaDioceseChurchListCrawler(source.urlsFor("さいたま教区")),
        CatholicTokyoArchdioceseChurchListCrawler(source.urlsFor("東京大司教区")),
        CatholicYokohamaDioceseChurchListCrawler(source.urlsFor("横浜教区")),
        CatholicNagoyaDioceseChurchListCrawler(source.urlsFor("名古屋教区")),
        CatholicKyotoDioceseChurchListCrawler(source.urlsFor("京都教区")),
        CatholicOsakaTakamatsuArchdioceseChurchListCrawler(source.urlsFor("大阪高松大司教区")),
        CatholicHiroshimaDioceseChurchListCrawler(source.urlsFor("広島教区")),
        CatholicFukuokaDioceseChurchListCrawler(source.urlsFor("福岡教区")),
        CatholicNagasakiArchdioceseChurchListCrawler(source.urlsFor("長崎大司教区")),
        CatholicOitaDioceseChurchListCrawler(source.urlsFor("大分教区")),
        CatholicKagoshimaDioceseChurchListCrawler(source.urlsFor("鹿児島教区")),
        CatholicNahaDioceseChurchListCrawler(source.urlsFor("那覇教区")),
    )
    private val crawlerByUrl = dioceseCrawlers
        .flatMap { crawler -> crawler.sourceUrls.map { canonicalPageUrl(it) to crawler } }
        .toMap()

    override val denominationId = "CATHOLIC_JP"
    override val denominationName = source.denominationName
    override val sourceUrl = "https://www.cbcj.catholic.jp/japan/diocese/"
    override val sourceUrls = dioceseCrawlers.flatMap(CatholicDioceseChurchListCrawler::sourceUrls)
    override val outputFileName = "catholic_jp-churches.json"

    init {
        require(source.denominationId == denominationId) {
            "Expected $denominationId source, got ${source.denominationId}"
        }
        require(crawlerByUrl.size == dioceseCrawlers.sumOf { it.sourceUrls.size }) {
            "Catholic diocese church-list URLs must be unique"
        }
    }

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> =
        requireNotNull(crawlerByUrl[canonicalPageUrl(url)]) { "No Catholic diocese crawler configured for $url" }.parsePage(url, html)

    override fun parseDetailPage(church: OfficialDenominationChurch, html: String): OfficialDenominationChurch =
        requireNotNull(dioceseCrawlers.singleOrNull { church.jurisdiction in it.jurisdictionNames }) {
            "No Catholic diocese detail parser configured for ${church.jurisdiction}"
        }.parseDetailPage(church, html)

    override fun merge(churches: List<OfficialDenominationChurch>): List<OfficialDenominationChurch> {
        val merged = super.merge(churches)
        dioceseCrawlers.forEach { crawler ->
            val count = merged.count { it.jurisdiction in crawler.jurisdictionNames }
            val minimum = minimumChurchesBySlug.getValue(crawler.dioceseSlug)
            require(count >= minimum) { "${crawler.dioceseSlug} Catholic directory unexpectedly contained only $count rows" }
        }
        return merged
    }

    private companion object {
        val minimumChurchesBySlug = mapOf(
            "sapporo" to 50, "sendai" to 50, "niigata" to 30, "saitama" to 50, "tokyo" to 70,
            "yokohama" to 55, "nagoya" to 50, "kyoto" to 45, "ostk" to 90, "hirosima" to 40,
            "fukuoka" to 50, "nagasaki" to 100, "oita" to 20, "kagosima" to 25, "naha" to 20,
        )
    }
}

private fun DenominationDirectorySource.urlsFor(jurisdictionName: String): List<String> =
    requireNotNull(jurisdictionList.singleOrNull { it.name == jurisdictionName }) {
        "Missing $jurisdictionName in $denominationId jurisdictionList"
    }.churchListUrlList.also { require(it.isNotEmpty()) { "$jurisdictionName has no church-list URLs" } }

private fun canonicalPageUrl(url: String): String = url.removeSuffix("/")

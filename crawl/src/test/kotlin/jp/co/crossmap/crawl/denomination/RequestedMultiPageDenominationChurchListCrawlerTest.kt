package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestedMultiPageDenominationChurchListCrawlerTest {
    @Test
    fun jecParsesPrefectureArticles() {
        val url = "https://www.jec-net.org/pref/tokyo/"
        val church = JECDenominationChurchListCrawler(listOf(url)).parsePage(url, """
            <article><h2 class="entry-title">桜台恵み平安キリスト教会</h2><div class="entry-content">
            教職者：我喜屋 明（主） 清水 恵満子 電話番号：03-3557-7607 ファクス：03-3557-7607
            メールアドレス：<a href="mailto:sakuradai@example.jp">mail</a> 住所：〒176-0011 東京都練馬区豊玉上2-13-11
            </div></article>
        """.trimIndent()).single()
        assertEquals("東京都", church.jurisdiction)
        assertEquals("〒176-0011 東京都練馬区豊玉上２−１３−１１", church.address)
    }

    @Test
    fun jfgcPreservesOfficialEnglishName() {
        val url = "https://www.japan-foursquare.jp/cont2/6.html"
        val church = JFGCDenominationChurchListCrawler(listOf(url)).parsePage(url, """
            <div class="church-item"><div class="church-name">「羊ケ丘シオン教会」 Hitujigaoka Zion Church</div>
            <p>〒062-0043 北海道札幌市豊平区福住3条3丁目7-2</p></div>
        """.trimIndent()).single()
        assertEquals("羊ケ丘シオン教会", church.name)
        assertEquals("Hitujigaoka Zion Church", church.localizedNames.single().name)
    }

    @Test
    fun jlcParsesRegionalCallouts() {
        val url = "http://www.jlc.or.jp/area/kanto/"
        val church = JLCDenominationChurchListCrawler(listOf(url)).parsePage(url, """
            <div class="fl-callout"><h3 class="fl-callout-title">六本木ルーテル教会</h3>
            <div class="fl-callout-text">〒106-0032 東京都港区六本木6-16-44 TEL 03-3405-9972 牧師 佐藤 太郎</div></div>
        """.trimIndent()).single()
        assertEquals("関東地区", church.jurisdiction)
        assertEquals("佐藤 太郎", church.ministers.single().name)
    }

    @Test
    fun kelcSeparatesNameAndPastor() {
        val url = "http://www.kelc.net/hyogo.html"
        val church = KELCDenominationChurchListCrawler(listOf(url)).parsePage(url, """
            <div class="detail_box"><h3>三田北摂教会　　牧師　末岡　成夫</h3><p>〒669-1537 三田市西山2-22-52 電話：079-564-6852</p></div>
        """.trimIndent()).single()
        assertEquals("三田北摂教会", church.name)
        assertEquals("末岡 成夫", church.ministers.single().name)
    }

    @Test
    fun liveMergesOfficialJaEnPtNamesByPostalCode() {
        val urls = listOf("https://livechurch.jp/location/ja/", "https://livechurch.jp/location/en/", "https://livechurch.jp/location/pt/")
        val crawler = LIVEDenominationChurchListCrawler(urls)
        fun page(name: String, address: String) = "<div class='x-col'><h1>$name</h1><div>$address<br>TEL: 053-527-0810</div></div>"
        val churches = urls.flatMap { url ->
            val name = when {
                "/ja/" in url -> "ライブチャーチ寸座"
                "/en/" in url -> "Live Church Sunza"
                else -> "LIVE CHURCH SUNZA"
            }
            crawler.parsePage(url, page(name, "〒431-1305 静岡県浜松市浜名区細江町気賀11417-1"))
        }
        val church = crawler.merge(churches).single()
        assertEquals("ライブチャーチ寸座", church.name)
        assertEquals(setOf("ja", "en", "pt"), church.localizedNames.map { it.languageCode }.toSet())
    }

    @Test
    fun jfecDiscoversAndEnrichesGalleryDetailPages() {
        val url = "https://www.doumeifukuin.net/shyozokukyoukai/"
        val crawler = JFECDenominationChurchListCrawler(listOf(url))
        val church = crawler.parsePage(url, """
            <h2>愛知県の教会</h2><div class="gallery"><dl class="gallery-item">
            <dt><a href="http://www.doumeifukuin.net/aihopechurch/"><img></a></dt>
            <dd class="gallery-caption">愛ホープチャーチ</dd></dl></div>
        """.trimIndent()).single()
        val enriched = crawler.parseDetailPage(church, """
            <main><article><p>住所：〒492-8145 愛知県稲沢市正明寺1丁目1-8<br>
            ウェッブページ：<a href="http://ai-hope-church.com">website</a><br>お問い合わせ：0587-23-8814</p></article></main>
        """.trimIndent())
        assertEquals("愛知県", enriched.jurisdiction)
        assertEquals("〒492-8145 愛知県稲沢市正明寺１丁目１−８", enriched.address)
        assertEquals("0587-23-8814", enriched.phone)
    }

    @Test
    fun gmiDiscoversAndEnrichesChapelDetailPages() {
        val url = "https://gmi.or.jp/chapels/"
        val crawler = GMIDenominationChurchListCrawler(listOf(url))
        val church = crawler.parsePage(url, """
            <ul class="chapels_boxs"><li class="clearfix"><div class="area_caption">大阪／八尾市</div>
            <h2 class="chapels_name">グレース大聖堂(GM)</h2><div class="linkBtn">
            <a href="https://gmi.or.jp/chapels/grace-cathedral/">詳細はこちら</a></div></li></ul>
        """.trimIndent()).single()
        val enriched = crawler.parseDetailPage(church, """
            <main><div class="pastor_block"><h2 class="name">金 大弘<span>キム デホン</span></h2>
            <p class="position">副代表牧師</p></div><p>〒581-0866 大阪府八尾市東山本新町1-15</p>
            <p>TEL: 072-995-6606 FAX: 072-996-4951</p></main>
        """.trimIndent())
        assertEquals("グレース大聖堂", enriched.name)
        assertEquals("大阪", enriched.jurisdiction)
        assertEquals("金 大弘", enriched.ministers.single().name)
    }
}

package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewDenominationChurchListCrawlerTest {
    @Test
    fun fgjaMapsTableColumnsAndExcludesTheFourRussianChurches() {
        val crawler = FGJADenominationChurchListCrawler()
        val rows = listOf(
            row("東京地方会", "フルゴスペル東京教会", "志垣 重政 牧師", "/tokyo.html"),
            row("東ロシア地方会", "フルゴスペルウラジオストック教会", "李 ミラン 牧師", "/russia.html"),
        )
        val churches = crawler.parse("<table>${rows.joinToString("")}</table>")
        assertEquals(listOf("フルゴスペル東京教会"), churches.map { it.name })
        assertEquals("東京地方会", churches.single().jurisdiction)
        assertEquals("志垣 重政", churches.single().ministers.single().name)
    }

    @Test
    fun cotnJpFollowsChurchLinksAndParsesDetailContactFields() {
        val crawler = COTNJPDenominationChurchListCrawler()
        val church = crawler.parse("""
            <div class='g-column -col2'><div class='column'><h3>国立教会</h3>
            <p class='c-body'>〒186-0003 東京都国立市富士見台2丁目 TEL 03-1234-5678 牧師：佐藤 太郎</p></div>
            <div class='column'><a href='../cm/kunitachi.html'><img alt='教会紹介'></a></div></div>
        """).single()
        val detail = crawler.parseDetailPage(church, detailHtml("国立教会", "naz100th※yahoo.co.jp"))
        assertEquals("〒186-0003 東京都国立市富士見台２丁目", detail.address)
        assertEquals("naz100th@yahoo.co.jp", detail.email)
        assertEquals("佐藤 太郎", detail.ministers.single().name)
    }

    @Test
    fun jbuMapsAChurchCardIncludingWebsiteAndPastor() {
        val church = JBUDenominationChurchListCrawler().parse(
            card("日本バプテスト同盟東京教会", "www.jbu.or.jp", external = "https://tokyo-baptist.example/"),
        ).single()
        assertEquals("https://tokyo-baptist.example/", church.websiteUrl)
        assertEquals("佐藤 太郎", church.ministers.single().name)
    }

    @Test
    fun tpkfMapsItsFourColumnTableAndMinisterRoles() {
        val html = """<table><tr><td><strong>房総中央キリスト教会</strong><br>牧師 刈込政弘</td><td>〒292-0012 千葉県木更津市牛袋100-58</td><td>TEL 090-1667-6224</td><td>関東</td></tr></table>"""
        val church = TPKFDenominationChurchListCrawler().parse(html).single()
        assertEquals("関東", church.jurisdiction)
        assertEquals("刈込政弘", church.ministers.single().name)
    }

    @Test
    fun bccMapsChurchCardContactFields() {
        val church = BCCDenominationChurchListCrawler().parse(
            "<div class='swell-block-column'><p><strong>基督兄弟団成増教会</strong></p><a href='https://narimasu.example/'>HP</a></div>",
        ).single()
        assertEquals("https://narimasu.example/", church.websiteUrl)
    }

    @Test
    fun bgcJpMapsChurchCardContactFields() {
        val church = BGCJPDenominationChurchListCrawler().parse("""
            <h2 class='wp-block-heading'>保谷バプテスト教会</h2>
            <figure class='wp-block-table'><table>
              <tr><th>住所</th><td>〒202-0001 東京都西東京市ひばりが丘1-2-3</td></tr>
              <tr><th>電話</th><td>042-123-4567 FAX 042-123-4568</td></tr>
              <tr><th>HP</th><td><a href='https://hoya.example/'>公式</a></td></tr>
              <tr><th>牧師</th><td>佐藤 太郎</td></tr>
              <tr><th>メール</th><td><a href='mailto:church@example.jp'>メール</a></td></tr>
            </table></figure>
        """).single()
        assertEquals("保谷バプテスト教会", church.name)
        assertEquals("church@example.jp", church.email)
    }

    @Test
    fun salvationArmyRecognizesCorpsAsAChurchEntity() {
        val church = SAJPDenominationChurchListCrawler().parse("""
            <div class='table-01__row'><h2 class='table-01__head'>救世軍神田小隊</h2>
            <div class='table-01__content'><p>〒101-0051 東京都千代田区神田神保町2-17</p>
            <p>TEL 03-1234-5678 FAX 03-1234-5679</p></div></div>
        """).single()
        assertEquals("救世軍神田小隊", church.name)
    }

    @Test
    fun jcbaKeepsSocialLinksSeparateFromTheOfficialWebsite() {
        val html = """<main><p class='wp-block-paragraph'><strong>仙台南光沢教会</strong><br>
            牧師・佐藤太郎<br>〒980-0001 宮城県仙台市青葉区一番町1-2-3<br>Tel: 022-123-4567<br>
            <a href='https://sendai-church.example/'>公式</a><a href='https://www.youtube.com/@sendai-church'>動画</a></p></main>"""
        val church = JCBADenominationChurchListCrawler().parsePage(JCBADenominationChurchListCrawler().sourceUrls.first(), html).single()
        assertEquals("https://sendai-church.example/", church.websiteUrl)
        assertEquals(SocialPlatform.YOUTUBE, church.socialProfiles.single().platform)
    }

    @Test
    fun pcjFollowsDetailPageForAddressPastorEmailAndWebsite() {
        val crawler = PCJDenominationChurchListCrawler()
        val church = crawler.parse("<a href='/churches/tokyo'>東京中会めぐみ教会</a>").single()
        val detail = crawler.parseDetailPage(church, detailHtml("東京中会めぐみ教会", "megumi@example.jp", "https://megumi.example/"))
        assertTrue(detail.address.contains("東京都"))
        assertEquals("megumi@example.jp", detail.email)
        assertEquals("https://megumi.example/", detail.websiteUrl)
        assertEquals("佐藤 太郎", detail.ministers.single().name)
    }

    @Test
    fun efcJpFollowsDetailPageButIgnoresNavigationLinks() {
        val crawler = EFCJPDenominationChurchListCrawler()
        val churches = crawler.parse("<a href='/church/tokyo'>東京福音自由教会</a><a href='/churchlist'>教会一覧</a>")
        assertEquals(1, churches.size)
        val detail = crawler.parseDetailPage(churches.single(), """
            <table><tr><td><strong>地区</strong></td><td>関東</td></tr>
            <tr><td><strong>所在地</strong></td><td>〒180-0001 東京都武蔵野市吉祥寺北町1-2-3</td></tr>
            <tr><td><strong>電話番号</strong></td><td>0422-123-4567</td></tr>
            <tr><td><strong>教職者名</strong></td><td>佐藤太郎</td></tr>
            <tr><td><strong>メール</strong></td><td><a href='mailto:tokyo@example.jp'>メール</a></td></tr>
            <tr><td><strong>ウェブサイト</strong></td><td><a href='https://tokyo-efc.example/'>公式</a></td></tr></table>
            <footer><a href='https://facebook.com/efcjapan'>団体Facebook</a><a href='http://www.inkthemes.com'>theme</a></footer>
        """)
        assertEquals("tokyo@example.jp", detail.email)
        assertTrue(detail.socialProfiles.isEmpty())
        assertEquals("https://tokyo-efc.example/", detail.websiteUrl)
    }

    @Test
    fun gecCombinesRegionPagesWithoutLosingThePageRelativeDetailUrl() {
        val crawler = GECDenominationChurchListCrawler()
        val church = crawler.parsePage(crawler.sourceUrls[1], """
            <h2 class='wp-block-heading'><span>大宮福音教会</span></h2>
            <p class='is-style-emboss_box'><strong>住所</strong>：埼玉県さいたま市大宮区桜木町1-2-3<br>
            <strong>電話</strong>：<a href='tel:048-123-4567'>048-123-4567</a><br>
            <strong>牧師</strong>：佐藤太郎</p>
            <div class='wp-block-button'><a href='https://omiya.example/'>教会ホームページへ</a></div>
        """).single()
        assertEquals("大宮福音教会", church.name)
        assertEquals("https://omiya.example/", church.websiteUrl)
    }

    private fun row(area: String, name: String, minister: String, href: String) =
        "<tr><td>$area</td><td>$name</td><td>$minister</td><td><a href='$href'>詳細はこちらから</a></td></tr>"

    private fun card(name: String, host: String, external: String = "", social: String = ""): String = """
        <article class="church-item">
          <h3><a href="https://$host/church/sample/">$name</a></h3>
          <p>〒186-0003 東京都国立市富士見台2丁目 TEL 03-1234-5678 FAX 03-1234-5679</p>
          <p>牧師 佐藤 太郎 E-mail church@example.jp</p>
          ${external.takeIf(String::isNotBlank)?.let { "<a href='$it'>公式サイト</a>" }.orEmpty()}
          ${social.takeIf(String::isNotBlank)?.let { "<a href='$it'>動画</a>" }.orEmpty()}
        </article>
    """

    private fun detailHtml(name: String, email: String, website: String = ""): String = """
        <main><h1>$name</h1><p>〒186-0003 東京都国立市富士見台2丁目 TEL 03-1234-5678</p>
        <p>牧師 佐藤 太郎 E-mail $email</p><a href="$website">教会公式サイト</a></main>
    """
}

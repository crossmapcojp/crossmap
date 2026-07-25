package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestedSinglePageDenominationChurchListCrawlerTest {
    @Test
    fun wjelcParsesCardsWithPrefectureContactAndSocialData() {
        val crawler = WJELCDenominationChurchListCrawler("https://www.wjelc.or.jp/about/churchlist/")
        val html = """
            <h2>千葉県 － １教会</h2>
            <div class="wpv-grid grid-1-4">
              <div data-href="https://www.wjelc.or.jp/shinden-elc/" class="linkarea">
                <h3>新田福音ルーテル教会<br>(市川新田キリスト教会)</h3>
                <p>牧師:石丸 潤一<br>〒272-0035 市川市新田2-19-20<br>0473-78-7553</p>
              </div>
              <a href="https://www.facebook.com/shinden/">Facebook</a>
            </div>
        """.trimIndent()

        val church = crawler.parse(html).single()

        assertEquals("新田福音ルーテル教会 (市川新田キリスト教会)", church.name)
        assertEquals("〒272-0035 千葉県市川市新田２−１９−２０", church.address)
        assertEquals("千葉県", church.jurisdiction)
        assertEquals("0473-78-7553", church.phone)
        assertEquals("石丸 潤一", church.ministers.single().name)
        assertEquals("https://facebook.com/shinden", church.socialProfiles.single().url)
    }

    @Test
    fun jacParsesDistrictTables() {
        val crawler = JACDenominationChurchListCrawler("https://jac-hij.sakura.ne.jp/profile.html")
        val html = """
            <h2>山陽教区</h2>
            <table class="sp-table"><tbody>
              <tr><th>教会名</th><th>牧師名</th><th>〒</th><th>住所</th><th>電話番号</th></tr>
              <tr><td><a href="http://iwakuni.example/">岩国キリスト教会</a></td><th>湯場 雅徳</th>
                <td>740-0032</td><td>山口県岩国市尾津町2-4-28</td><td>0827-31-8234</td></tr>
            </tbody></table>
        """.trimIndent()

        val church = crawler.parse(html).single()

        assertEquals("岩国キリスト教会", church.name)
        assertEquals("〒740-0032 山口県岩国市尾津町２−４−２８", church.address)
        assertEquals("山陽教区", church.jurisdiction)
        assertEquals("湯場 雅徳", church.ministers.single().name)
        assertEquals("http://iwakuni.example/", church.websiteUrl)
    }

    @Test
    fun obcPreservesOfficialEnglishNameWithChurchContactData() {
        val crawler = OBCDenominationChurchListCrawler("http://okinawabaptist.com/?page_id=2")
        val html = """
            <h3>中北部 / MIDDLE NORTH</h3>
            <h3>名護バプテスト教会<br>Nago Baptist Church</h3>
            <h5>牧師：田畑凡男</h5>
            <p>〒905-0015 名護市大南3-7-10 TEL：0980-52-1095 E-mail：nago@example.jp</p>
            <p><a href="https://nago.example/">ホームページ</a></p>
            <h3>金武バプテスト教会<br>Kin Baptist Church</h3>
        """.trimIndent()

        val church = crawler.parse(html).first()

        assertEquals("名護バプテスト教会", church.name)
        assertEquals("Nago Baptist Church", church.localizedNames.single().name)
        assertEquals("〒905-0015 沖縄県名護市大南３−７−１０", church.address)
        assertEquals("中北部", church.jurisdiction)
        assertEquals("田畑凡男", church.ministers.single().name)
        assertEquals("nago@example.jp", church.email)
        assertEquals("https://nago.example/", church.websiteUrl)
    }

    @Test
    fun jmbcPreservesOfficialEnglishNameAndDistrict() {
        val crawler = JMBCDenominationChurchListCrawler("https://jmbc.japan-mb.com/church/")
        val html = """
            <h2 class="wp-block-heading">豊能地区 (Toyono Block)</h2>
            <div class="wp-block-columns"><div class="wp-block-column">
              <h5 class="wp-block-heading">石橋キリスト教会（Ishibashi Christ Church）</h5>
              <p>大阪府池田市石橋2丁目17-10-A</p><p>主任牧師：船橋 誠<br>副牧師：南野 浩則</p>
              <a href="https://www.mbishibashi.com/">ホームページ</a>
            </div><div class="wp-block-column"><img src="church.jpg"></div></div>
        """.trimIndent()

        val church = crawler.parse(html).single()

        assertEquals("石橋キリスト教会", church.name)
        assertEquals("Ishibashi Christ Church", church.localizedNames.single().name)
        assertEquals("大阪府池田市石橋２丁目１７−１０−A", church.address)
        assertEquals("豊能地区", church.jurisdiction)
        assertEquals(listOf("船橋 誠", "南野 浩則"), church.ministers.map { it.name })
        assertEquals("https://www.mbishibashi.com/", church.websiteUrl)
    }

    @Test
    fun seikyodanParsesTheDirectFrameTable() {
        val crawler = SEIKYODANDenominationChurchListCrawler("https://www.seikyodan.com/shyozoku5.html")
        val html = """
            <a name="kanto"></a><table><tr>
              <td><a href="https://ogose.example/">越生教会</a></td>
              <td>〒350-0415 埼玉県入間郡越生町1106-1</td>
            </tr></table>
        """.trimIndent()

        val church = crawler.parse(html).single()

        assertEquals("越生教会", church.name)
        assertEquals("〒350-0415 埼玉県入間郡越生町１１０６−１", church.address)
        assertEquals("関東地区", church.jurisdiction)
        assertEquals("https://ogose.example/", church.websiteUrl)
    }

    @Test
    fun wmcParsesBlockTables() {
        val crawler = WMCDenominationChurchListCrawler("https://worldmission.or.jp/church/")
        val html = """
            <section><h2 class="elementor-heading-title">近畿ブロック</h2><table>
              <tr><td>教会名</td><td>教職者名</td><td>〒</td><td>住所</td><td>TEL</td><td>FAX</td></tr>
              <tr><td><a href="https://bethany.example/">(宗)ベタニヤチャーチ</a></td><td>吉田 茂樹、吉田 芳幸</td>
                <td>577-0801</td><td>大阪府東大阪市小阪1-13-13</td><td>06-6782-7333</td><td>06-6782-7170</td></tr>
            </table></section>
        """.trimIndent()

        val church = crawler.parse(html).single()

        assertEquals("ベタニヤチャーチ", church.name)
        assertEquals("〒577-0801 大阪府東大阪市小阪１−１３−１３", church.address)
        assertEquals("近畿ブロック", church.jurisdiction)
        assertEquals(listOf("吉田 茂樹", "吉田 芳幸"), church.ministers.map { it.name })
    }

    @Test
    fun jlbcPairsChurchHeadingsWithTheirInformationTable() {
        val crawler = JLBCDenominationChurchListCrawler("https://clbj.org/church/")
        val html = """
            <h2 class="wp-block-heading">青森県</h2>
            <h3 class="wp-block-heading">八戸聖書キリスト教会</h3>
            <div class="wp-block-columns"><table>
              <tr><th>牧師</th><td>澤田 隆一</td></tr><tr><th>住所</th><td>〒039-1166 青森県八戸市根城字大久保55-44</td></tr>
              <tr><th>Tel/Fax</th><td>0178-43-3091</td></tr><tr><th>HP</th><td><a href="https://hachinohe.clbj.org/">HP</a></td></tr>
            </table></div>
            <h2 class="wp-block-heading">秋田県</h2>
        """.trimIndent()

        val church = crawler.parse(html).single()

        assertEquals("八戸聖書キリスト教会", church.name)
        assertEquals("〒039-1166 青森県八戸市根城字大久保５５−４４", church.address)
        assertEquals("青森県", church.jurisdiction)
        assertEquals("澤田 隆一", church.ministers.single().name)
        assertEquals("https://hachinohe.clbj.org/", church.websiteUrl)
    }

    @Test
    fun fmcJpParsesDistrictTables() {
        val crawler = FMCJPDenominationChurchListCrawler("https://fmcjp.org/?page_id=61")
        val church = crawler.parse("""
            <h3>西部教区</h3><table><tr><td><a href="https://fmcjp.org/?page_id=94">三輪キリスト教会</a></td>
            <td>牧師 植田直也<br>〒 669-1513 三田市三輪 1-2-11<br>℡ 079-562-4560</td></tr></table>
        """.trimIndent()).single()

        assertEquals("三輪キリスト教会", church.name)
        assertEquals("〒669-1513 三田市三輪１−２−１１", church.address)
        assertEquals("西部教区", church.jurisdiction)
        assertEquals("植田直也", church.ministers.single().name)
    }

    @Test
    fun nfkSeparatesHalfWidthKanaReadingAndAddsHangul() {
        val crawler = NFKDenominationChurchListCrawler("https://nihonfukuin.imagodei.jp/所属教会/")
        val church = crawler.parse("""
            <p>〇東北地区</p><table><tr><td>米沢愛の教会</td><td>〒992-0012</td><td>山形県米沢市1-2-3</td>
            <td>車幸任ﾁｬﾍﾝﾘﾑ</td><td>0238-00-0000</td></tr></table>
        """.trimIndent()).single()

        assertEquals("車幸任", church.ministers.single().name)
        assertEquals("車幸任（チャヘンリム）", church.ministers.single().localizedNames.single { it.languageCode == "ja" }.name)
        assertEquals("차행림", church.ministers.single().localizedNames.single { it.languageCode == "ko" }.name)
        assertEquals("東北地区", church.jurisdiction)
    }

    @Test
    fun mskkUsesTheExistingNskkCanonicalIdAndParsesChurchSections() {
        val crawler = MSKKDenominationChurchListCrawler("https://nskk.gr.jp/church/")
        val church = crawler.parse("""
            <h2 class="wp-block-heading">北部教区</h2><h2 class="wp-block-heading">新潟県</h2>
            <h2 class="wp-block-heading">長岡聖契キリスト教会</h2><div><p>住所：〒940-0041 新潟県長岡市学校町1-2-3</p>
            <p>牧師：契約 太郎 TEL：0258-00-0000</p><a href="https://nagaoka.example/">website</a></div>
        """.trimIndent()).single()

        assertEquals("NSKK", crawler.denominationId)
        assertEquals("長岡聖契キリスト教会", church.name)
        assertEquals("北部教区 / 新潟県", church.jurisdiction)
        assertEquals("契約 太郎", church.ministers.single().name)
    }

    @Test
    fun adventParsesHeadingSectionsAndSuffixPastors() {
        val church = ADVENTDenominationChurchListCrawler("https://nihonadobento.wordpress.com/list/").parse("""
            <h1><a href="https://kariya.example/">刈谷キリスト教会</a></h1>
            <p>〒448-0806 愛知県刈谷市松栄町2丁目5-2<br>0566(23)7948 藤永 康牧師</p><h1>メニュー</h1>
        """.trimIndent()).single()
        assertEquals("刈谷キリスト教会", church.name)
        assertEquals("藤永 康", church.ministers.single().name)
        assertEquals("愛知県", church.jurisdiction)
    }

    @Test
    fun fukuinDendoParsesOneHeadingSection() {
        val church = FUKUINDENDODenominationChurchListCrawler("https://church.ne.jp/list.html").parse("""
            <h2>新潟聖書教会</h2><p>住所：</p><p><a href="https://maps.example/">〒950-0973 新潟県新潟市中央区上近江3-37-28</a></p>
            <p>電話：025-283-8324</p><p>HP：<a href="https://niigata.example/">website</a></p><h2>終わり</h2>
        """.trimIndent()).single()
        assertEquals("新潟聖書教会", church.name)
        assertEquals("新潟県", church.jurisdiction)
        assertEquals("025-283-8324", church.phone)
    }

    @Test
    fun jebExpandsWifeGivenNameAndKeepsReviewedKoreanName() {
        val crawler = JEBDenominationChurchListCrawler("https://nihon-dendoutai.kyoukai.jp/church/")
        val churches = crawler.parse("""
            <h2>塩屋キリスト教会</h2><p>〒655-0873 神戸市垂水区青山台1丁目20-5</p><p>TEL 078-752-4271</p><p>田口 学、愛子</p>
            <h2>湊川伝道館</h2><p>〒652-0811 神戸市兵庫区新開地2丁目6-7</p><p>朴 鐘皖、孫 理紹</p><h2>メニュー</h2>
        """.trimIndent())
        assertEquals(listOf("田口 学", "田口 愛子"), churches.first().ministers.map { it.name })
        assertEquals("박종완", churches.last().ministers.first().localizedNames.single().name)
        assertEquals("〒655-0873 兵庫県神戸市垂水区青山台１丁目２０−５", churches.first().address)
    }

    @Test
    fun seiyakuParsesOfficialTable() {
        val church = SEIYAKUDenominationChurchListCrawler("https://www.seiyaku.jp/?page_id=1316").parse("""
            <table><tr><td>教会名</td><td>牧師</td><td>所在地</td><td>連絡先</td></tr>
            <tr><td><a href="https://uniting.example/">ユナイティングチャーチ一宮教会</a></td><td>斉藤隆二</td>
            <td>〒701-1204 岡山県岡山市北区今岡21-1</td><td>TEL.0863-81-8366</td></tr></table>
        """.trimIndent()).single()
        assertEquals("斉藤隆二", church.ministers.single().name)
        assertEquals("岡山県", church.jurisdiction)
    }
}

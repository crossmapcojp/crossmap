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
            row("地方会", "教会名", "牧師", "/header"),
            row("東京地方会", "フルゴスペル東京教会", "志垣 重政 牧師", "/tokyo.html"),
            row("東ロシア地方会", "フルゴスペルウラジオストック教会", "李 ミラン 牧師", "/russia.html"),
        )
        val churches = crawler.parse("<table>${rows.joinToString("")}</table>")
        assertEquals(listOf("フルゴスペル東京教会"), churches.map { it.name })
        assertEquals("東京地方会", churches.single().jurisdiction)
        assertEquals("志垣 重政", churches.single().ministers.single().name)

        val hosted = crawler.parse("""
            <table><tr><td>関西地方会</td><td><a href='https://www.fgtc.jp/church/142/'>フルゴスペル枚方教会</a></td><td>牧師 佐藤 太郎</td></tr></table>
        """).single()
        val detailed = crawler.parseDetailPage(hosted, """
            <main><div class='church-detail-box'><p>〒573-0001 大阪府枚方市1-2-3 TEL 072-123-4567</p></div>
            <a href='https://www.fgtc.jp/'>純福音東京教会</a>
            <a href='https://fgtc.news/'>家族新聞</a></main>
        """)
        assertEquals("", detailed.websiteUrl)
    }

    @Test
    fun cotnJpFollowsChurchLinksAndParsesDetailContactFields() {
        val crawler = COTNJPDenominationChurchListCrawler()
        val church = crawler.parse("""
            <div class='g-column -col2'><div class='column'><h3>国立教会</h3>
            <p class='c-body'>〒186-0003 東京都国立市富士見台2丁目 TEL 03-1234-5678 牧師：佐藤 太郎</p></div>
            <div class='column'><a href='../cm/kunitachi.html'><img alt='教会紹介'></a></div></div>
        """).single()
        val detail = crawler.parseDetailPage(church, """
            <main>
              <div class='column -col8 -col_main'><p>教団からのお知らせ</p></div>
              <div class='column -col8 -col_main'>
                <h1>国立教会</h1><p>〒186-0003 東京都国立市富士見台2丁目 TEL 03-1234-5678</p>
                <p>牧師：大山 裕昭（おおやま ひろあき） YouTubeで教会の雰囲気、礼拝風景が分かります。
                前半には、ジョイジョイのスタッフによる子どもメッセージがあり、後半には牧師の分かりやすいメッセージがあります。</p>
                <p>E-mail naz100th※yahoo.co.jp</p>
                <a href='https://facebook.com/kunitachi.nazarene'>国立教会 Facebook</a>
              </div>
              <footer><a href='https://facebook.com/NazareneOfficial'>教団 Facebook</a></footer>
            </main>
        """)
        assertEquals("〒186-0003 東京都国立市富士見台２丁目", detail.address)
        assertEquals("naz100th@yahoo.co.jp", detail.email)
        assertEquals("大山 裕昭（おおやま ひろあき）", detail.ministers.single().name)
        assertEquals(listOf("https://facebook.com/kunitachi.nazarene"), detail.socialProfiles.map { it.url })
        val legacyDetail = crawler.parseDetailPage(church, """
            <body><div><p>〒457-0845 愛知県名古屋市南区観音町4-23</p>
            <p>牧師 ： 松　時穂（まつ しほ） １９５９年４月、名古屋南教会の献堂式が執り行われました。</p></div></body>
        """)
        assertEquals("松 時穂（まつ しほ）", legacyDetail.ministers.single().name)
        val assignedDetail = crawler.parseDetailPage(church, """
            <body><p>〒794-0041 愛媛県今治市松本町3-2-8</p>
            <p>牧師：山陽四国地区担当（塩﨑悟史［水島教会］）</p></body>
        """)
        assertEquals("塩﨑悟史", assignedDetail.ministers.single().name)
    }

    @Test
    fun jbuMapsAChurchCardIncludingWebsiteAndPastor() {
        val crawler = JBUDenominationChurchListCrawler()
        val church = crawler.parse("""
            <table><tr><td><a href='./churchdital.php?chid=2'>根室キリスト教会</a><br>
            <a href='./churchdital.php?chid=2'>帯広伝道所</a></td></tr></table>
        """).single()
        assertEquals("根室キリスト教会帯広伝道所", church.name)
        val detail = crawler.parseDetailPage(church, """
            <html><head><title>根室キリスト教会帯広伝道所</title></head><body><table>
            <tr><td>所在地</td><td>080-0015</td></tr>
            <tr><td></td><td>北海道帯広市西5条南 33-84 Webペ−ジは「根室キリスト教会帯広伝道所 Facebook」で検索</td></tr>
            <tr><td>連絡先</td><td>TEL 0155-49-3547</td></tr>
            <tr><td></td><td>FAX 0155-49-3548</td></tr>
            <tr><td></td><td><a href='mailto:heiyaka@example.jp'>メール</a></td></tr>
            <tr><td>Webサイト</td><td><a href='../link/conf_link.php?org=church&amp;url=https://tokyo-baptist.example/'>https://tokyo-baptist.example/</a></td></tr>
            <tr><td>牧 師</td><td>佐藤 太郎</td></tr>
            </table></body></html>
        """)
        assertEquals("〒080-0015 北海道帯広市西５条南３３−８４", detail.address)
        assertEquals("0155-49-3547", detail.phone)
        assertEquals("0155-49-3548", detail.fax)
        assertEquals("heiyaka@example.jp", detail.email)
        assertEquals("https://tokyo-baptist.example/", detail.websiteUrl)
        assertEquals("佐藤 太郎", detail.ministers.single().name)
        val mixedRoles = crawler.parseDetailPage(church, """
            <html><head><title>奈良佐保キリスト教会</title></head><body><table>
            <tr><td>所在地</td><td>〒630-0001 奈良県奈良市1-2-3</td></tr>
            <tr><td>牧師</td><td>(伝) 三宅 英子 ・ (協) 藤岡 荘一 ・ 石塚 多美子</td></tr>
            </table></body></html>
        """)
        assertEquals(listOf("三宅 英子", "藤岡 荘一", "石塚 多美子"), mixedRoles.ministers.map { it.name })
        assertEquals(listOf("evangelist", "cooperating_pastor", "pastor"), mixedRoles.ministers.map { it.roleId })
        val suffixRole = crawler.parseDetailPage(church, """
            <html><head><title>カチン平和教会</title></head><body><table>
            <tr><td>所在地</td><td>〒169-0001 東京都新宿区1-2-3</td></tr>
            <tr><td>宣教師</td><td>ドゥムピャ カーラ ・ (伝)</td></tr>
            </table></body></html>
        """)
        assertEquals("evangelist", suffixRole.ministers.single().roleId)
    }

    @Test
    fun denominationAddressNormalizationStripsDirectoryInstructions() {
        mapOf(
            "〒235-0016 神奈川県横浜市磯子区磯子 7-18-10 Webペ−ジは「磯子の丘教会 Facebook」で検索" to
                "〒235-0016 神奈川県横浜市磯子区磯子 ７−１８−１０",
            "〒182-0023 東京都調布市染地2-2-44 Google マップの地図に行く 京王線布田駅より徒歩15分" to
                "〒182-0023 東京都調布市染地２−２−４４",
            "〒192-0032 東京都八王子市石川町2605-3 Google マップの地図に行く JR北八王子駅から徒歩15分" to
                "〒192-0032 東京都八王子市石川町２６０５−３",
            "〒203-0004 東京都東久留米市氷川台1-8-13 Google マップの地図に行く 東久留米駅より徒歩約10分" to
                "〒203-0004 東京都東久留米市氷川台１−８−１３",
        ).forEach { (source, expected) ->
            assertEquals(expected, DirectoryCrawlerSupport.normalizeAddress(source))
        }
    }

    @Test
    fun tpkfMapsItsFourColumnTableAndMinisterRoles() {
        val crawler = TPKFDenominationChurchListCrawler()
        val html = """<table><tr><td><a href='https://tpkf.org/localch_kanto.html#boso'><strong>房総中央キリスト教会</strong></a><br>牧師 刈込政弘</td><td>〒292-0012 千葉県木更津市牛袋100-58</td><td>TEL　090-1667-6224 FAX　0439-65-4056</td><td>関東</td></tr></table>"""
        val church = crawler.parse(html).single()
        assertEquals("関東", church.jurisdiction)
        assertEquals("090-1667-6224", church.phone)
        assertEquals("0439-65-4056", church.fax)
        assertEquals("刈込政弘", church.ministers.single().name)
        val branch = crawler.parse("""<table><tr><td><span class='nolink'>大阪キリスト福音教会名古屋伝道所</span><br>副牧師 今田雄司</td><td>〒463-0033 愛知県名古屋市守山区森孝東1-913</td><td>TEL 090-5853-9291</td><td>中部</td></tr></table>""").single()
        assertEquals("大阪キリスト福音教会名古屋伝道所", branch.name)
        val regionalPage = """
            <main>
              <div class='LineBox1_wrapper1'><h5><a id='boso'></a>房総中央キリスト教会</h5>
                <ul><li>〒292-0012 千葉県木更津市牛袋100-58</li></ul></div>
              <div class='LineBox1_wrapper1'><h5><a id='kaisei'></a>開成クリスチャンセンター</h5>
                <ul><li>〒258-0024 神奈川県足柄上郡開成町中之名358-1</li>
                <li>Instagram <a href='https://www.instagram.com/kaiseicc/'>kaiseicc</a></li></ul></div>
            </main>
        """
        assertTrue(crawler.parseDetailPage(church, regionalPage).socialProfiles.isEmpty())
        val kaisei = church.copy(
            name = "開成クリスチャンセンター",
            denominationChurchListDetailPage = "https://tpkf.org/localch_kanto.html#kaisei",
        )
        assertEquals("https://instagram.com/kaiseicc", crawler.parseDetailPage(kaisei, regionalPage).socialProfiles.single().url)
        val machida = crawler.parse("""
            <table><tr><td><strong>町田純福音教会</strong><br>主任牧師 上田正美 牧師 小川祐司
            牧師 石井すみれ 派遣牧師（ブライダル） 堀満</td>
            <td>〒194-0021 東京都町田市中町1-9-21</td><td>TEL 042-728-8520</td><td>関東</td></tr></table>
        """).single()
        assertEquals(
            listOf("上田正美", "小川祐司", "石井すみれ", "堀満"),
            machida.ministers.map { it.name },
        )
        assertEquals(
            listOf("senior_pastor", "pastor", "pastor", "dispatched_pastor"),
            machida.ministers.map { it.roleId },
        )
        val maruoka = crawler.parse("""
            <table><tr><td><strong>丸岡福音キリスト教会</strong><br>牧師 菅原純次 教師 庭井要</td>
            <td>〒910-0242 福井県坂井市丸岡町西里丸岡10-9</td><td>TEL 090-1310-3923</td><td>中部</td></tr></table>
        """).single()
        assertEquals(listOf("菅原純次", "庭井要"), maruoka.ministers.map { it.name })
        assertEquals(listOf("pastor", "minister"), maruoka.ministers.map { it.roleId })
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
        val crawler = JCBADenominationChurchListCrawler()
        val html = """<main><p class='wp-block-paragraph'><strong>仙台南光沢教会</strong><br>
            牧師・佐藤太郎<br>〒980-0001 宮城県仙台市青葉区一番町1-2-3<br>Tel: 022-123-4567<br>
            <a href='https://sendai-church.example/'>公式</a><a href='https://www.youtube.com/@sendai-church'>動画</a></p></main>"""
        val church = crawler.parsePage(crawler.sourceUrls.first(), html).single()
        assertEquals("https://sendai-church.example/", church.websiteUrl)
        assertEquals(SocialPlatform.YOUTUBE, church.socialProfiles.single().platform)
        val complex = crawler.parsePage(crawler.sourceUrls.first(), """
            <main><p class='wp-block-paragraph'><strong>福島第一聖書バプテスト教会</strong><br>
            主任牧師・佐藤将司 ユースパスター/副牧師・栗田義人 ユースパスター・川上広樹
            アドバイザー牧師・佐藤彰 宣教師・モニカ・ブルッテル<br>
            〒971-8185 福島県いわき市泉町7-19-1 Tel: 0246-38-5757</p></main>
        """).single()
        assertEquals(
            listOf("佐藤将司", "栗田義人", "川上広樹", "佐藤彰", "モニカ・ブルッテル"),
            complex.ministers.map { it.name },
        )
        assertEquals(
            listOf("senior_pastor", "associate_pastor", "youth_pastor", "advisor_pastor", "missionary"),
            complex.ministers.map { it.roleId },
        )
    }

    @Test
    fun pcjFollowsDetailPageForAddressPastorEmailAndWebsite() {
        val crawler = PCJDenominationChurchListCrawler()
        val church = crawler.parse("""
            <a href='/'>日本長老教会 Home</a><a href='/home/'>「日本長老教会」とは</a>
            <a href='/churches/'>所属教会</a><a href='/churches/tokyo'>東京中会めぐみ教会</a>
        """).single()
        val detail = crawler.parseDetailPage(church, detailHtml("東京中会めぐみ教会", "megumi@example.jp", "https://megumi.example/"))
        assertEquals("〒182-0023 東京都調布市染地２−２−４４", detail.address)
        assertEquals("megumi@example.jp", detail.email)
        assertEquals("https://megumi.example/", detail.websiteUrl)
        assertEquals("朴 権出（パク・コンチョル）", detail.ministers.single().name)
        assertEquals(5, detail.ministers.single().localizedRoleNames.size)
        val koreanName = crawler.parseDetailPage(church, """
            <main><p>〒290-0001 千葉県市原市1-2-3</p>
            <p>窓口 韓（ハン）ビョンソブ牧師 所属中会 東関東中会</p></main>
        """)
        assertEquals("韓（ハン）ビョンソブ", koreanName.ministers.single().name)
    }

    @Test
    fun efcJpFollowsDetailPageButIgnoresNavigationLinks() {
        val crawler = EFCJPDenominationChurchListCrawler()
        val churches = crawler.parse("""
            <a href='/posts/churchinfo/tokyo'>東京福音自由教会</a>
            <a href='/churchlist'>教会リスト</a><a href='/churchlist'>全国教会リスト</a><a href='/churchmap'>全国教会マップ</a>
        """)
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
            <strong>牧師</strong>：佐藤太郎<br>&emsp;&emsp;鈴木花子</p>
            <div class='wp-block-button'><a href='https://omiya.example/'>教会ホームページへ</a></div>
        """).single()
        assertEquals("大宮福音教会", church.name)
        assertEquals("https://omiya.example/", church.websiteUrl)
        assertEquals(listOf("佐藤太郎", "鈴木花子"), church.ministers.map { it.name })
    }

    @Test
    fun orthodoxJpCombinesThreeJurisdictionsAndEnrichesOfficialDetailContacts() {
        val crawler = OrthodoxJpDenominationChurchListCrawler()
        val church = crawler.parsePage(crawler.sourceUrls[1], """
            <main><a href='annai/image/sendaiin.jpg'><img alt='仙台ハリストス正教会'></a>
            <a href='annai/h-sendai.html'><strong>仙台ハリストス正教会</strong></a>
            <a href='annai/h-sendai.html'>仙台ハリストス正教会</a></main>
        """).single()
        assertEquals("仙台ハリストス正教会", church.name)
        assertEquals("東日本主教々区", church.jurisdiction)
        assertEquals("https://www.orthodoxjapan.jp/annai/h-sendai.html", church.denominationChurchListDetailPage)

        val detailed = crawler.parseDetailPage(church, """
            <h1>仙台ハリストス正教会・生神女福音聖堂</h1>
            <table class='tcontact'><caption>所在地・問合せ</caption><tbody>
              <tr><th>住所</th></tr><tr><td>〒980-0021<br>宮城県仙台市青葉区中央3丁目4-20<br><a href='https://maps.example/'>所在地マップ</a></td></tr>
              <tr><th>TEL/FAX</th></tr><tr><td>022-225-2744/022-224-3080</td></tr>
              <tr><th>管轄</th></tr><tr><td>司祭ルカ　田畑隆平</td></tr>
              <tr><th>E-mail</th></tr><tr><td><a href='mailto:orthodox@example.jp'>orthodox@example.jp</a></td></tr>
              <tr><th>URL</th></tr><tr><td><a href='https://sendai-orthodox.example/'>公式</a></td></tr>
            </tbody></table>
        """)
        assertEquals("〒980-0021 宮城県仙台市青葉区中央３丁目４−２０", detailed.address)
        assertEquals("022-225-2744", detailed.phone)
        assertEquals("022-224-3080", detailed.fax)
        assertEquals("orthodox@example.jp", detailed.email)
        assertEquals("https://sendai-orthodox.example/", detailed.websiteUrl)
        assertEquals("田畑隆平", detailed.ministers.single().name)
        assertEquals("priest", detailed.ministers.single().roleId)
    }

    @Test
    fun anglicanJpParsesTheNationalPdfTextByDioceseAndExcludesOfficesAndSchools() {
        val crawler = AnglicanJpDenominationChurchListCrawler()
        val churches = crawler.parseExtractedText("""
            ＋北海道教区 // 北海道
            北海道教区事務所 北海道札幌市北区北15条西5-1-12 011-111-1111
            札幌キリスト教会（主教座聖堂） 北海道札幌市北区北8条西6-2-18 011-747-7339
            香蘭女学校礼拝堂 東京都品川区旗の台6-22-21 03-1111-1111
            ＋東京教区 // 東京都
            三光教会 東京都品川区旗の台6-22-24 03-3781-2554
        """.trimIndent())

        assertEquals(listOf("札幌キリスト教会（主教座聖堂）", "三光教会"), churches.map { it.name })
        assertEquals(listOf("北海道教区", "東京教区"), churches.map { it.jurisdiction })
        assertEquals("北海道札幌市北区北８条西６−２−１８", churches.first().address)
        assertEquals("011-747-7339", churches.first().phone)
    }

    @Test
    fun catholicIndexDiscoversCurrentCbcjDiocesesBeforeResolvingOfficialSites() {
        val dioceses = CatholicJpDioceseIndex.parseIndex("""
            <a href='/japan/diocese/sapporo/'>■ カトリック札幌教区（北海道）</a>
            <a href='/japan/diocese/ostk/'>■ カトリック大阪高松大司教区</a>
            <a href='/japan/diocese/sapporo/'>duplicate</a>
            <a href='/japan/statistics/'>統計</a>
        """)
        assertEquals(listOf("sapporo", "ostk"), dioceses.map { it.slug })
        val resolved = CatholicJpDioceseIndex.resolveOfficialWebsite(dioceses.first(), """
            <a href='https://www.csd.or.jp'>https://www.csd.or.jp</a>
            <a href='https://www.catholic-education.jp/'>学校教育委員会</a>
        """)
        assertEquals("https://www.csd.or.jp", resolved.officialWebsiteUrl)
    }

    @Test
    fun catholicSapporoParsesItsDistrictTablesUsingConfiguredUrls() {
        val crawler = CatholicSapporoDioceseChurchListCrawler(listOf("https://www.csd.or.jp/sapporo"))
        val churches = crawler.parse("""
            <div class='row'><div><h4 id='ttl-iwamizawa'></h4></div></div>
            <div class='row'><div><table class='table table-underline'>
              <tr><th>小教区</th><td>カトリック岩見沢教会</td></tr>
              <tr><th>住所</th><td>〒068-0024 北海道岩見沢市4条西3丁目</td></tr>
              <tr><th>TEL・FAX</th><td>0126-22-1111 / 0126-22-2222</td></tr>
              <tr><th>主任司祭</th><td>佐藤 太郎</td></tr>
              <tr><th>E-mail</th><td><a href='mailto:church@example.jp'>church@example.jp</a></td></tr>
              <tr><th>ホームページ</th><td><a href='https://iwamizawa.example/'>公式</a></td></tr>
            </table></div></div>
        """)
        val church = churches.single()
        assertEquals("札幌地区", church.jurisdiction)
        assertEquals("〒068-0024 北海道岩見沢市４条西３丁目", church.address)
        assertEquals("0126-22-1111", church.phone)
        assertEquals("0126-22-2222", church.fax)
        assertEquals("church@example.jp", church.email)
        assertEquals("https://iwamizawa.example/", church.websiteUrl)
        assertEquals("佐藤 太郎", church.ministers.single().name)
    }

    @Test
    fun catholicJpDispatcherReusesJurisdictionUrlsFromTheSourceCatalog() {
        val names = listOf(
            "札幌教区", "仙台教区", "新潟教区", "さいたま教区", "東京大司教区", "横浜教区", "名古屋教区", "京都教区",
            "大阪高松大司教区", "広島教区", "福岡教区", "長崎大司教区", "大分教区", "鹿児島教区", "那覇教区",
        )
        val source = jp.co.crossmap.crawl.DenominationDirectorySource(
            id = "catholic_jp",
            denominationId = "CATHOLIC_JP",
            denominationName = "カトリック中央協議会",
            jurisdictionList = names.mapIndexed { index, name ->
                jp.co.crossmap.crawl.DenominationJurisdictionSource(
                    id = index.toString(), name = name, kind = jp.co.crossmap.crawl.JurisdictionKind.DIOCESE,
                    churchListUrlList = listOf("https://example.test/$index"),
                )
            },
        )
        val crawler = CatholicJpDenominationChurchListCrawler(source)

        assertEquals(names.indices.map { "https://example.test/$it" }, crawler.sourceUrls)
    }

    @Test
    fun catholicSendaiUsesTheDistrictInEachParishDetailUrl() {
        val crawler = CatholicSendaiDioceseChurchListCrawler(
            listOf("https://sendai.catholic.jp/diocese/parishes/"),
        )
        val listed = crawler.parse("""
            <h2>第1地区（青森・岩手）</h2><ul>
              <li><a href='/diocese/parishes/d1/namiuchi/'>カトリック浪打教会</a></li>
            </ul>
        """).single()
        val church = crawler.parseDetailPage(listed, """
            <main><ul class='church__access'>
              <li>〒030-0961 青森県青森市浪打1-20-6</li>
              <li>TEL：017-741-5903</li><li>FAX：017-718-8215</li>
            </ul></main>
        """)

        assertEquals("仙台教区・第1地区", church.jurisdiction)
        assertEquals("〒030-0961 青森県青森市浪打１−２０−６", church.address)
        assertEquals("017-741-5903", church.phone)
        assertEquals("017-718-8215", church.fax)
    }

    @Test
    fun catholicNiigataReadsDistrictNavigationAndParishDetails() {
        val crawler = CatholicNiigataDioceseChurchListCrawler(
            listOf("http://www.catholic-niigata.net/churches"),
        )
        val listed = crawler.parse("""
            <li class='menu-item'><a href='/churches/akitadist'>秋田地区</a><ul class='sub-menu'>
              <li class='menu-item'><a href='/akita'>カトリック秋田教会</a></li>
            </ul></li>
        """).single()
        val church = crawler.parseDetailPage(listed, """
            <div class='entry-content'>
              <p><strong>主任司祭：</strong>飯野耕太郎神父</p>
              <p><strong>住所：</strong>〒010-0875 秋田市千秋明徳町1-48</p>
              <p><strong>電話：</strong>018-832-3254 <strong>FAX：</strong>018-833-7035</p>
              <p><a href='http://akita-cath.example/'>公式</a></p>
            </div>
        """)

        assertEquals("新潟教区・秋田地区", church.jurisdiction)
        assertEquals("〒010-0875 秋田市千秋明徳町１−４８", church.address)
        assertEquals("018-832-3254", church.phone)
        assertEquals("018-833-7035", church.fax)
        assertEquals("飯野耕太郎", church.ministers.single().name)
    }

    @Test
    fun catholicTokyoParsesDirectoryLinksAndDetailContactFields() {
        val crawler = CatholicTokyoArchdioceseChurchListCrawler(
            listOf("https://tokyo.catholic.jp/archdiocese/parishes/tokyo/"),
        )
        val listed = crawler.parse("""
            <ul><li class='info__item'><a href='/archdiocese/parishes/tokyo/akabane/'>
              <span class='info__detail'>カトリック赤羽教会</span></a></li></ul>
        """).single()
        val church = crawler.parseDetailPage(listed, """
            <main><p>住所・連絡先 〒115-0045 東京都北区赤羽2-1-12 電話：03-3901-2902 Fax：03-3902-3508</p>
              <p><a href='https://akabane.example/'>ホームページ</a></p>
              <h3>主任司祭</h3><p>平 孝之</p>
            </main>
        """)

        assertEquals("〒115-0045 東京都北区赤羽２−１−１２", church.address)
        assertEquals("03-3901-2902", church.phone)
        assertEquals("03-3902-3508", church.fax)
        assertEquals("https://akabane.example/", church.websiteUrl)
        assertEquals("平 孝之", church.ministers.single().name)
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
        <main><h1>$name</h1><p>〒182-0023 東京都調布市染地2-2-44 Google マップの地図に行く 京王線布田駅より徒歩15分 TEL 03-1234-5678</p>
        <p>窓口 朴 権出（パク・コンチョル）牧師 所属中会 東京中会</p>
        <p>E-mail $email</p><a href="$website">教会公式サイト</a></main>
    """
}

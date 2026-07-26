package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.crawl.NOT_DETERMINED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DenominationChurchListCrawlerTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun denominationDirectoryCacheIsReusableForThirtyDays() {
        val fetchedAt = Instant.parse("2026-07-01T00:00:00Z")

        assertTrue(DenominationDirectoryCachePolicy.isFresh(fetchedAt.toString(), fetchedAt.plus(Duration.ofDays(29))))
        assertTrue(DenominationDirectoryCachePolicy.isFresh(fetchedAt.toString(), fetchedAt.plus(Duration.ofDays(30))))
        assertFalse(
            DenominationDirectoryCachePolicy.isFresh(
                fetchedAt.toString(),
                fetchedAt.plus(Duration.ofDays(30)).plusSeconds(1),
            ),
        )
        assertFalse(DenominationDirectoryCachePolicy.isFresh("invalid", fetchedAt))
    }

    @Test
    fun invalidHttpCharsetFallsThroughToTheHtmlMetaCharset() {
        val html =
            """<meta http-equiv="Content-Type" content="text/html; charset=Shift_JIS">""" +
                """<p>日本福音教会連合 ℡ 0561(72)1166</p>"""
        val bytes = html.toByteArray(java.nio.charset.Charset.forName("windows-31j"))

        val decoded = decodeDenominationHtml(bytes, "text/html; charset=none")
        assertTrue(decoded.contains("日本福音教会連合"))
        assertTrue(decoded.contains("℡ 0561(72)1166"))
    }

    @Test
    fun uccjParserReadsRealChurchRowsAndSkipsDioceseHeadings() {
        val html = """
            <table class="kyokai">
              <tr><th>教会名</th><th>教区</th><th>〒</th><th>所在地</th><th>TEL</th><th>FAX</th></tr>
              <tr><td class="name">【東海教区】</td><td class="kyouku"></td><td class="postno"></td><td class="address"></td><td class="tel"></td><td class="fax"></td></tr>
              <tr><td class="name">修善寺教会</td><td class="kyouku">東海</td><td class="postno">410-2407</td><td class="address">伊豆市柏久保455-1</td><td class="tel">0558-72-2300</td><td class="fax">0558-72-2300</td></tr>
              <tr><td class="name">沼津教会</td><td class="kyouku">東海</td><td class="postno">410-0888</td><td class="address">沼津市末広町95</td><td class="tel">055-963-5657</td><td class="fax">055-963-5657</td></tr>
            </table>
        """.trimIndent()

        val churches = UCCJDenominationChurchListCrawler().parse(html)

        assertEquals(listOf("修善寺教会", "沼津教会"), churches.map(OfficialDenominationChurch::name))
        assertEquals("〒410-2407 伊豆市柏久保455-1", churches.first().address)
        assertEquals("東海", churches.first().jurisdiction)
        assertTrue(churches.all { it.membershipStatus == OfficialChurchMembershipStatus.LISTED })
    }

    @Test
    fun jbcParserReadsRealTableShapeAndMarksPendingApplicantIneligible() {
        val html = """
            <table class="church-table">
              <tr><td class="kyoku" colspan="5">中国四国バプテスト連合</td></tr>
              <tr><td></td><td>教会名称</td><td>郵便番号</td><td>教会住所</td><td>教会電話番号</td></tr>
              <tr><td class="td-center">201</td><td class="c-name">岡山バプテスト教会</td><td>〒700-0825</td><td>岡山県岡山市北区田町1丁目7-28</td><td>TEL 086-222-7684</td></tr>
              <tr><td class="td-center">202</td><td class="c-name">太田ビジョンキリスト教会 ※第72回定期総会において連盟加盟申請中</td><td>〒373-0821</td><td>群馬県太田市下浜田町1085-50</td><td></td></tr>
            </table>
        """.trimIndent()

        val churches = JBCDenominationChurchListCrawler().parse(html)

        assertEquals("岡山バプテスト教会", churches.first().name)
        assertEquals("〒700-0825 岡山県岡山市北区田町1丁目7-28", churches.first().address)
        assertEquals("中国四国バプテスト連合", churches.first().jurisdiction)
        assertEquals("太田ビジョンキリスト教会", churches.last().name)
        assertEquals(OfficialChurchMembershipStatus.PENDING, churches.last().membershipStatus)
        assertFalse(churches.last().eligibleForDenominationEvidence)
    }

    @Test
    fun jbbfParserReadsOfficialAddressBookWithoutTreatingMemberNamesAsAliases() {
        val html = """
            <p><b><a id="shizuoka" name="shizuoka"></a>静岡県</b></p>
            <hr />
            <p><b><a href="http://www.shimizubbc.com/">清水聖書バプテスト教会</a></b><br />
            牧師：濱田 献<br />〒424-0832　静岡県静岡市清水区入江南町７－１１　TEL054-366-3804</p>
            <p><b><a href="http://sennbonn.cocolog-wbs.com/blog/">千本浜聖書バプテスト教会（清水教会伝道所）</a></b><br />
            伝道師：道下 義嗣<br />〒410-0866 静岡県沼津市市道町４ー１３ TEL055-913-3742</p>
        """.trimIndent()

        val churches = JBBFDenominationChurchListCrawler().parse(html)

        assertEquals(listOf("清水聖書バプテスト教会", "千本浜聖書バプテスト教会"), churches.map { it.name })
        assertEquals("〒424-0832　静岡県静岡市清水区入江南町７－１１", churches.first().address)
        assertEquals("静岡県", churches.first().jurisdiction)
        assertEquals("http://www.shimizubbc.com/", churches.first().websiteUrl)
        assertEquals("濱田 献", churches.first().ministers.single().name)
        assertEquals("pastor", churches.first().ministers.single().roleId)
        assertEquals("道下 義嗣", churches.last().ministers.single().name)
        assertEquals("evangelist", churches.last().ministers.single().roleId)
        assertEquals("清水教会伝道所", churches.last().note)
    }

    @Test
    fun jaccParserReadsRealChurchRowsAndExtractsAllFields() {
        val html = """
            <table border="1" class="honbun9p">
              <tr bgcolor="#99FF66">
                <td>教　　会</td><td>牧　　師</td><td>住所→地図</td>
              </tr>
              <tr bgcolor="#FFFF99">
                <td><a href="db_disp.php?recordID=test"><strong>	  新札幌聖書教会</strong></a></td>
                <td><strong>朴 永基</strong></td>
                <td><strong>〒004-0033 北海道<a href="http://maps.google.co.jp/maps?q=test">北海道札幌市厚別区上野幌三条6-13-15</a></strong></td>
              </tr>
              <tr>
                <td>[101・1]北海道宣教区</td>
                <td>李 相勲</td>
                <td>電話：[011(892)5233]　FAX：[011(892)5274]</td>
              </tr>
              <tr>
                <td>礼拝：10:30,19:00</td>
                <td></td>
                <td>e-Mail：<a href="mailto:test@test.com">test@test.com</a></td>
              </tr>
              <tr>
                <td>祈祷会：(水)10:30</td>
                <td></td>
                <td><a href="https://kirisutonoai.org/" target="_blank">https://kirisutonoai.org/</a></td>
              </tr>
              <tr>
                <td colspan="3">★愛が満ちあふれる教会</td>
              </tr>
              <tr bgcolor="#FFFF99">
                <td><a href="db_disp.php?recordID=test2"><strong>	  新札幌福音教会</strong></a></td>
                <td><strong>野口 隆英</strong></td>
                <td><strong>〒004-0001 北海道<a href="http://maps.google.co.jp/maps?q=test2">札幌市厚別区厚別東一条3丁目10-1</a></strong></td>
              </tr>
              <tr>
                <td>[102・1]北海道宣教区</td>
                <td></td>
                <td>電話：[011(897)3345]　FAX：[011(897)3345]</td>
              </tr>
              <tr>
                <td>礼拝：10:30</td>
                <td></td>
                <td>e-Mail：<a href="mailto:"></a></td>
              </tr>
              <tr>
                <td>祈祷会：(火)14:00</td>
                <td></td>
                <td><a href="https://shinsapporo.jimdofree.com" target="_blank">https://shinsapporo.jimdofree.com</a></td>
              </tr>
              <tr>
                <td colspan="3">★</td>
              </tr>
            </table>
        """.trimIndent()

        val churches = JACCDenominationChurchListCrawler().parse(html)

        assertEquals(2, churches.size)
        assertEquals("新札幌聖書教会", churches[0].name)
        assertEquals("〒004-0033 北海道北海道札幌市厚別区上野幌三条6-13-15", churches[0].address)
        assertEquals("[101・1]北海道宣教区", churches[0].jurisdiction)
        assertEquals("011(892)5233", churches[0].phone)
        assertEquals("011(892)5274", churches[0].fax)
        assertEquals("https://kirisutonoai.org/", churches[0].websiteUrl)
        assertEquals("朴 永基", churches[0].ministers.single().name)

        assertEquals("新札幌福音教会", churches[1].name)
        assertEquals("〒004-0001 北海道札幌市厚別区厚別東一条3丁目10-1", churches[1].address)
        assertEquals("[102・1]北海道宣教区", churches[1].jurisdiction)
        assertEquals("011(897)3345", churches[1].phone)
        assertEquals("011(897)3345", churches[1].fax)
        assertEquals("https://shinsapporo.jimdofree.com", churches[1].websiteUrl)
    }

    @Test
    fun jaccParserHandlesEdgeCases() {
        val html = """
            <table border="1">
              <tr bgcolor="#FFFF99">
                <td><a href="db_disp.php?recordID=test"><strong>	  〒空教会</strong></a></td>
                <td><strong>牧師</strong></td>
                <td><strong>〒030-0123 青森県<a href="http://maps.google.co.jp/maps?q=test">青森市大矢沢字里見80-6</a></strong></td>
              </tr>
              <tr>
                <td>[201・2]東北宣教区</td>
                <td></td>
                <td>電話：[090-3890-7001(本多)]　FAX：[なし]</td>
              </tr>
              <tr>
                <td>礼拝：14:00</td>
                <td></td>
                <td>e-Mail：<a href="mailto:"></a></td>
              </tr>
              <tr>
                <td>祈祷会：(水)13:30</td>
                <td></td>
                <td><a href="nasukougenchurch.com" target="_blank">nasukougenchurch.com</a></td>
              </tr>
              <tr>
                <td colspan="3">★</td>
              </tr>
              <tr bgcolor="#FFFF99">
                <td><a href="db_disp.php?recordID=test2"><strong>	  宗）二本松福音の家教会</strong></a></td>
                <td><strong>吉兼 剛</strong></td>
                <td><strong>〒969-1403 福島県<a href="http://maps.google.co.jp/maps?q=test2">二本松市渋川字上払川100-6</a></strong></td>
              </tr>
              <tr>
                <td>[205・2]東北宣教区</td>
                <td></td>
                <td>電話：[0243(24)1492]　FAX：[]</td>
              </tr>
              <tr>
                <td>礼拝：11:00</td>
                <td></td>
                <td>e-Mail：<a href="mailto:gospel-house@gol.com">gospel-house@gol.com</a></td>
              </tr>
              <tr>
                <td>祈祷会：(水)10:30</td>
                <td></td>
                <td><a href="" target="_blank"></a></td>
              </tr>
              <tr>
                <td colspan="3">★</td>
              </tr>
            </table>
        """.trimIndent()

        val churches = JACCDenominationChurchListCrawler().parse(html)

        assertEquals(2, churches.size)
        assertEquals("〒空教会", churches[0].name)
        assertEquals("090-3890-7001(本多)", churches[0].phone)
        assertEquals("", churches[0].fax)
        assertEquals("https://nasukougenchurch.com", churches[0].websiteUrl)

        assertEquals("宗）二本松福音の家教会", churches[1].name)
        assertEquals("0243(24)1492", churches[1].phone)
        assertEquals("", churches[1].fax)
        assertEquals("", churches[1].websiteUrl)
    }

    @Test
    fun jhcParserReadsRealChurchRowsFromMultiTableLayout() {
        val html = """
            <table>
              <tr style="background-color: #c200c2">
                <td>ブロック</td><td>教区</td><td>教会</td><td>郵便番号</td><td>住所</td><td>電話</td><td>FAX</td><td>備考</td>
              </tr>
              <tr style="background-color: #000000">
                <td>関東</td><td>東京教区</td><td>東京ホーリネス教会</td><td>113-0032</td><td>東京都文京区弥生1-1-1</td><td>03-3811-1234</td><td>03-3811-1235</td><td></td>
              </tr>
              <tr style="background-color: #000000">
                <td>関東</td><td>東京教区</td><td>横浜ホーリネス教会</td><td>220-0011</td><td>横浜市西区北幸1-2-3</td><td>045-123-4567</td><td></td><td></td>
              </tr>
            </table>
        """.trimIndent()

        val churches = JHCDenominationChurchListCrawler().parse(html)

        assertEquals(2, churches.size)
        assertEquals("東京ホーリネス教会", churches[0].name)
        assertEquals("〒113-0032 東京都文京区弥生1-1-1", churches[0].address)
        assertEquals("東京教区", churches[0].jurisdiction)
        assertEquals("03-3811-1234", churches[0].phone)
        assertEquals("03-3811-1235", churches[0].fax)

        assertEquals("横浜ホーリネス教会", churches[1].name)
        assertEquals("〒220-0011 横浜市西区北幸1-2-3", churches[1].address)
        assertEquals("045-123-4567", churches[1].phone)
        assertEquals("", churches[1].fax)
    }

    @Test
    fun rcjParserReadsRealChurchSectionsWithPostalAddresses() {
        val html = """
            <div class="list_area">
              <section>
                <h3><a href="detail.php?id=1">札幌キリスト改革派教会</a></h3>
                <h4>〒060-0061 北海道札幌市中央区北一条西4-1-2</h4>
                <div class="comment_area"><p>牧師は佐藤 太郎。2018年4月に辻幸宏牧師が着任しました。</p></div>
              </section>
              <section>
                <h3><a href="detail.php?id=2">函館改革派教会</a></h3>
                <h4>〒040-0082 北海道函館市昭和4-15-10</h4>
                <div class="comment_area"><p>代理牧師は大宮教会の辻幸宏(つじ ゆきひろ)です。</p></div>
              </section>
            </div>
        """.trimIndent()

        val churches = RCJDenominationChurchListCrawler().parse(html)

        assertEquals(2, churches.size)
        assertEquals("札幌キリスト改革派教会", churches[0].name)
        assertEquals("〒060-0061 北海道札幌市中央区北一条西4-1-2", churches[0].address)
        assertEquals(listOf("佐藤 太郎", "辻幸宏"), churches[0].ministers.map { it.name })

        assertEquals("函館改革派教会", churches[1].name)
        assertEquals("〒040-0082 北海道函館市昭和4-15-10", churches[1].address)
        assertEquals(listOf("辻幸宏(つじ ゆきひろ)"), churches[1].ministers.map { it.name })
    }

    @Test
    fun igmParserReadsRealChurchTableRowsWithThAndTd() {
        val html = """
            <table>
              <tr><th><a href="https://example.com/tokyo">東京中野教会</a></th>
                <td>牧師：田中 太郎<br>〒165-0023 東京都中野区中野5-30-10<br>TEL 03-3368-1234　FAX 03-3368-1235</td></tr>
              <tr><th><a href="https://example.com/okosho">大江戸教会</a></th>
                <td>牧師：佐藤 花子<br>〒160-0023 東京都新宿区西新宿1-1-1<br>TEL 03-3344-5678</td></tr>
            </table>
        """.trimIndent()

        val churches = IGMDenominationChurchListCrawler().parse(html)

        assertEquals(2, churches.size)
        assertEquals("東京中野教会", churches[0].name)
        assertEquals("https://example.com/tokyo", churches[0].websiteUrl)
        assertEquals("03-3368-1234", churches[0].phone)
        assertEquals("03-3368-1235", churches[0].fax)
        assertEquals("田中 太郎", churches[0].ministers.single().name)
        assertEquals(5, churches[0].ministers.single().localizedRoleNames.size)

        assertEquals("大江戸教会", churches[1].name)
        assertEquals("https://example.com/okosho", churches[1].websiteUrl)
        assertEquals("03-3344-5678", churches[1].phone)
        assertEquals("", churches[1].fax)
    }

    @Test
    fun existingDedicatedCrawlersAreSinglePageCrawlers() {
        val crawlers: List<DenominationChurchListCrawler> = listOf(
            UCCJDenominationChurchListCrawler(),
            JBCDenominationChurchListCrawler(),
            JBBFDenominationChurchListCrawler(),
            JACCDenominationChurchListCrawler(),
            JHCDenominationChurchListCrawler(),
            RCJDenominationChurchListCrawler(),
            IGMDenominationChurchListCrawler(),
        )

        assertTrue(crawlers.all { it is SinglePageDenominationChurchListCrawler })
    }

    @Test
    fun jagParserReadsChurchCardsAndSkipsDioceseIntroduction() {
        val html = """
            <div class="church church-info-hokkaido-kyoku church-info-kyoku_introduction">
              <div class="vk_post_imgOuter"><span class="vk_post_imgOuter_singleTermLabel">北海道教区 | Hokkaido Kyoku</span></div>
              <div class="vk_post_body"><h5 class="vk_post_title"><a href="https://j-ag.org/church/hokkaido-kyoku/">北海道教区のご紹介</a></h5></div>
            </div>
            <div class="church church-info-hokkaido-kyoku">
              <div class="vk_post_imgOuter"><span class="vk_post_imgOuter_singleTermLabel">北海道教区 | Hokkaido Kyoku</span></div>
              <div class="vk_post_body">
                <h5 class="vk_post_title"><a href="https://j-ag.org/church/takasu/">鷹栖キリスト教会</a></h5>
                <p class="vk_post_excerpt">礼拝：毎週日曜日 牧師 藤田克裕 住所 〒071-1224 北海道上川郡鷹栖町北野東4条1丁目7-11 電話 0166-59-3530 FAX 0166-59-3530 […]</p>
              </div>
            </div>
        """.trimIndent()

        val churches = JAGDenominationChurchListCrawler().parse(html)

        assertEquals(1, churches.size)
        assertEquals("鷹栖キリスト教会", churches.single().name)
        assertEquals("〒071-1224 北海道上川郡鷹栖町北野東4条1丁目7-11", churches.single().address)
        assertEquals("北海道教区", churches.single().jurisdiction)
        assertEquals("0166-59-3530", churches.single().phone)
        assertEquals("0166-59-3530", churches.single().fax)
        assertEquals("", churches.single().websiteUrl)
        assertEquals("https://j-ag.org/church/takasu/", churches.single().denominationChurchListDetailPage)
    }

    @Test
    fun jagDetailPagesProvideCompleteNormalizedAddressesAndActualChurchWebsite() {
        val crawler = JAGDenominationChurchListCrawler()
        val cases = listOf(
            "岸和田福音キリスト教会" to "〒596-0825 大阪府岸和田市土生町2-25-20",
            "姫路神召キリスト教会" to "〒670-0965 兵庫県姫路市東延末2-73",
            "武庫川純福音キリスト教会" to "〒660-0084 兵庫県尼崎市武庫川町3丁目32-1",
            "御影神愛キリスト教会" to "〒658-0054 兵庫県神戸市東灘区御影中町1丁目7-15",
            "横須賀キリスト教会" to "〒239-0807 神奈川県横須賀市根岸町4丁目39-7",
            "福岡西部福音キリスト教会" to "〒819-0001 福岡県福岡市西区小戸3丁目54-52",
        )

        cases.forEach { (name, expectedAddress) ->
            val listed = OfficialDenominationChurch(
                name = name,
                denominationChurchListDetailPage = "https://j-ag.org/church/detail/",
            )
            val detailHtml = """
                <table>
                  <tr><td>住所</td><td>$expectedAddress　＊JR最寄駅から徒歩10分</td></tr>
                  <tr><td>主任牧師</td><td>佐藤 太郎</td></tr>
                  <tr><td>ホームページ</td><td><a href="https://church.example/$name">公式サイト</a></td></tr>
                </table>
            """.trimIndent()

            val detailed = crawler.parseDetailPage(listed, detailHtml)

            assertEquals(expectedAddress, detailed.address, name)
            assertEquals("https://church.example/$name", detailed.websiteUrl, name)
            assertEquals("senior_pastor", detailed.ministers.single().roleId, name)
            assertEquals(listed.denominationChurchListDetailPage, detailed.denominationChurchListDetailPage)
        }
    }

    @Test
    fun ministerRolesRetainNamesAndAllFiveLocalizedLabels() {
        val ministers = ChurchMinisterParser.parse("主任牧師：山田 太郎　副牧師：佐藤 花子　伝道師：金 美愛　牧師（兼任）：李 民守")

        assertEquals(listOf("senior_pastor", "associate_pastor", "evangelist", "concurrent_pastor"), ministers.map { it.roleId })
        ministers.forEach { minister ->
            assertEquals(setOf("ja", "en", "ko", "pt", "id"), minister.localizedRoleNames.map { it.languageCode }.toSet())
            assertTrue(minister.localizedRoleNames.all { it.name.isNotBlank() })
        }
    }

    @Test
    fun ministerParserRejectsNarrativeRoleMentionsAndTrimsContactFields() {
        assertTrue(ChurchMinisterParser.parse("牧師先生をお迎えして、月に一度礼拝を行っています").isEmpty())
        assertTrue(ChurchMinisterParser.parse("当教会には牧師が不在です").isEmpty())
        assertEquals("小菅香世子", ChurchMinisterParser.parse("牧師 小菅香世子（ 電話 090-1111-2222").single().name)
        assertEquals(
            listOf("小菅香世子", "小菅剛"),
            ChurchMinisterParser.fromRoleAndNames("牧師", "小菅香世子（主管牧師）小菅剛").map { it.name },
        )
    }

    @Test
    fun jelcParserReadsChurchContactAndPastor() {
        val churches = JELCDenominationChurchListCrawler().parse(
            """<table><tr><td><a href='/church/tokyo'>東京教会</a></td><td>〒169-0072 東京都新宿区大久保1-1-1</td><td>主任牧師：山田 太郎</td><td>TEL 03-1111-2222</td></tr></table>""",
        )

        assertEquals("東京教会", churches.single().name)
        assertEquals("〒169-0072 東京都新宿区大久保１−１−１", churches.single().address)
        assertEquals("senior_pastor", churches.single().ministers.single().roleId)
    }

    @Test
    fun jccParserUsesCanonicalCcjIdAndReadsLegacyTableRows() {
        val crawler = JCCDenominationChurchListCrawler()
        val churches = crawler.parse(
            """<table><tr><td><b>日本キリスト教会帯広教会</b></td><td>〒080-0016 北海道帯広市西6条南22丁目1-9</td><td>牧師：鈴木 一郎</td><td>電話 0155-11-2222</td></tr></table>""",
        )

        assertEquals("CCJ", crawler.denominationId)
        assertEquals("日本キリスト教会帯広教会", churches.single().name)
        assertEquals("鈴木 一郎", churches.single().ministers.single().name)

        val legacy = crawler.parse(
            """<table><tr><td>函館相生教会</td><td>０４０−００１１</td><td>北海道函館市本町２９−８</td><td>0138-52-7035</td><td>0138-31-5181</td><td>粂 広国（担）<br>李 愛（伝）</td></tr></table>""",
        ).single()
        assertEquals("〒040-0011 北海道函館市本町２９−８", legacy.address)
        assertEquals(listOf("lead_pastor", "evangelist"), legacy.ministers.map { it.roleId })
        assertEquals(listOf("粂 広国", "李 愛"), legacy.ministers.map { it.name })
    }

    @Test
    fun sdaJpParserReadsWordPressChurchBlock() {
        val churches = SDAJPDenominationChurchListCrawler().parse(
            """<article><h3>東京中央教会</h3><p>〒150-0001 東京都渋谷区神宮前1-2-3</p><p>牧師：田中 健</p><p>TEL 03-2222-3333</p></article>""",
        )

        assertEquals("東京中央教会", churches.single().name)
        assertEquals("pastor", churches.single().ministers.single().roleId)
    }

    @Test
    fun tleaParserReadsPhoneAddressAndEvangelist() {
        val churches = TLEADenominationChurchListCrawler().parse(
            """<table><tr><td><strong>TLEA札幌教会</strong></td><td>〒060-0001 北海道札幌市中央区北1条西1-1</td><td>伝道師：佐々木 愛</td><td>電話 011-222-3333</td></tr></table>""",
        )

        assertEquals("TLEA札幌教会", churches.single().name)
        assertEquals("evangelist", churches.single().ministers.single().roleId)
    }

    @Test
    fun hejParserFindsDetailLinksAndEnrichesMinisterData() {
        val crawler = HEJDenominationChurchListCrawler()
        val listed = crawler.parse("""<a href='/branch/sagano'><img alt='嵯峨野教会'></a>""").single()
        val detailed = crawler.parseDetailPage(
            listed,
            """<main><h1>嵯峨野教会</h1><p>〒616-0001 京都府京都市右京区嵯峨1-2-3</p><p>牧師：佐藤 恵</p><p>TEL 075-111-2222</p></main>""",
        )

        assertEquals("https://seiiesukai.org/branch/sagano", listed.denominationChurchListDetailPage)
        assertEquals("佐藤 恵", detailed.ministers.single().name)
        assertTrue(detailed.address.startsWith("〒616-0001"))
    }

    @Test
    fun jecaParserMergesRegionalWebsiteAndContactRows() {
        val crawler = JECADenominationChurchListCrawler()
        val websiteRows = crawler.parse("""<table><tr><td>北見めぐみキリスト教会</td><td><a href='https://kitami.example/'>Home page</a></td></tr></table>""")
        val contactRows = crawler.parse("""<table><tr><td>北見めぐみキリスト教会</td><td>〒090-0001 北海道北見市北1-2-3</td><td>牧師：金田 光</td><td>TEL 0157-11-2222</td></tr></table>""")
        val merged = crawler.merge(websiteRows + contactRows).single()

        assertEquals("https://kitami.example/", merged.websiteUrl)
        assertTrue(merged.address.startsWith("〒090-0001"))
        assertEquals("金田 光", merged.ministers.single().name)
    }

    @Test
    fun jccjParserReadsLabelledChurchTableAndMultipleRoles() {
        val churches = JCCJDenominationChurchListCrawler().parse(
            """
            <table class='table_localchurch'>
              <tr><th>教会名</th><td>荻窪栄光教会</td></tr>
              <tr><th>住所</th><td>〒167-0032 東京都杉並区天沼3-1-1</td></tr>
              <tr><th>主任牧師</th><td>山本 真</td></tr>
              <tr><th>伝道師</th><td>李 愛</td></tr>
              <tr><th>電話</th><td>03-3333-4444</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals("荻窪栄光教会", churches.single().name)
        assertEquals(listOf("senior_pastor", "evangelist"), churches.single().ministers.map { it.roleId })

        val liveLayout = JCCJDenominationChurchListCrawler().parse(
            """<table class='table_localchurch'><tr><td><h5>札幌羊ヶ丘教会</h5><ul><LI>〒004-0846 <LI>北海道札幌市清田区清田六条1丁目1-23 <LI>TEL 011-883-3790 <LI>牧師　小菅香世子（主管牧師）<br>小菅剛 <LI>定期集会</ul></td></tr></table>""",
        ).single()
        assertEquals("〒004-0846 北海道札幌市清田区清田六条１丁目１−２３", liveLayout.address)
        assertEquals(listOf("senior_pastor", "pastor"), liveLayout.ministers.map { it.roleId })
        assertEquals(listOf("小菅香世子", "小菅剛"), liveLayout.ministers.map { it.name })
    }

    @Test
    fun kccjParserReadsPastorAndExcludesEveryNonChurchEntityCategory() {
        val excluded = listOf("総会事務局", "在日韓国基督教会館", "桜本保育園", "在日総会神学校", "永生苑", "ケアハウスセットンの家")
        val rows = buildString {
            append("<table><tr><td>99</td><td><a href='church_view.php?id=99'>東京教会</a></td><td>担任牧師 金 太郎</td><td>03-1111-2222</td><td>〒169-0051 東京都新宿区西早稲田2-3-18</td></tr>")
            excluded.forEachIndexed { index, name ->
                append("<tr><td>$index</td><td>$name</td><td>牧師 誤登録</td><td>03-0000-0000</td><td>〒169-0051 東京都新宿区西早稲田2-3-18</td></tr>")
            }
            append("</table>")
        }
        val churches = KCCJDenominationChurchListCrawler().parse(rows)

        assertEquals(listOf("東京教会"), churches.map { it.name })
        assertEquals("lead_pastor", churches.single().ministers.single().roleId)
        assertEquals("金 太郎", churches.single().ministers.single().name)
    }

    @Test
    fun reconciliationRejectsRealSameCityNameContainmentFalsePositives() {
        val root = Files.createTempDirectory("crossmap-denomination-identity")
        try {
            Files.createDirectories(root.resolve("catalog"))
            val googleChurches = listOf(
                church("google:11549556222669181947", "日本キリスト教会帯広教会", "〒080-0016 北海道帯広市西６条南２２丁目１−９", NOT_DETERMINED),
                church("google:akita", "秋田キリスト教会", "〒010-1607 秋田県秋田市新屋南浜町８−１４", NOT_DETERMINED),
                church("google:takatsuki", "日本キリスト教会高槻教会", "〒569-0802 大阪府高槻市北園町９−１７", NOT_DETERMINED),
                church("google:urawa", "日本キリスト教会浦和教会", "〒330-0062 埼玉県さいたま市浦和区仲町４丁目８−２", NOT_DETERMINED),
                church("google:shibukawa", "日本キリスト教団渋川教会", "〒377-0008 群馬県渋川市渋川２２２０", NOT_DETERMINED),
                church("google:hachinohe", "八戸聖書キリスト教会", "〒039-1166 青森県八戸市根城大久保５５−４４", NOT_DETERMINED),
            )
            val officialChurches = listOf(
                OfficialDenominationChurch("帯広キリスト教会", "〒080-0838 北海道帯広市大空町4-6-6"),
                OfficialDenominationChurch("秋田福音キリスト教会", "〒010-1436 秋田県秋田市大住3-9-7"),
                OfficialDenominationChurch("高槻キリスト教会", "〒569-0818 大阪府高槻市桜ヶ丘南町22-3"),
                OfficialDenominationChurch("浦和キリスト教会", "埼玉県さいたま市浦和区高砂3-10-4"),
                OfficialDenominationChurch("渋川キリスト教会", "群馬県渋川市渋川928-3"),
                OfficialDenominationChurch("八戸キリスト教会", "〒039-1166 青森県八戸市根城 ９−７−６"),
            )
            googleChurches.zip(officialChurches).forEach { (google, official) ->
                assertFalse(
                    ChurchIdentity(google.name, google.address, google.websiteUrl)
                        .matches(ChurchIdentity(official.name, official.address, official.websiteUrl)),
                    "${google.name} must not match ${official.name}",
                )
            }
            val catalogFile = root.resolve("catalog/churches.json")
            Files.writeString(catalogFile, json.encodeToString(googleChurches))
            val report = OfficialDenominationChurchListReconciler(json = json).reconcile(
                catalogFile,
                listOf(
                    OfficialDenominationChurchList(
                        denominationId = "JAG",
                        denominationName = "日本アッセンブリーズ・オブ・ゴッド教団",
                        sourceUrl = "https://j-ag.org/church-info/",
                        fetchedAt = "2026-07-23T00:00:00Z",
                        churches = officialChurches,
                    ),
                ),
            )

            assertEquals(0, report.assigned)
            assertEquals(6, report.unmatchedOfficialEntries)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runnerLoadsAndAggregatesEveryJagDirectoryPage() {
        val root = Files.createTempDirectory("crossmap-jag-list")
        try {
            val crawler = JAGDenominationChurchListCrawler()
            val loadedUrls = linkedSetOf<String>()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { url, _ ->
                    loadedUrls += url
                    val html = when (url) {
                        crawler.sourceUrls.first() -> """
                            <div class="church church-info-hokkaido-kyoku">
                              <div class="vk_post_imgOuter"><span class="vk_post_imgOuter_singleTermLabel">北海道教区 | Hokkaido Kyoku</span></div>
                              <div class="vk_post_body">
                                <h5 class="vk_post_title"><a href="https://j-ag.org/church/takasu/">鷹栖キリスト教会</a></h5>
                                <p class="vk_post_excerpt">住所 〒071-1224 北海道上川郡鷹栖町北野東4条1丁目7-11 電話 0166-59-3530</p>
                              </div>
                            </div>
                        """.trimIndent()
                        "https://j-ag.org/church/takasu/" -> """
                            <table>
                              <tr><td>住所</td><td>〒071-1224 北海道上川郡鷹栖町北野東4条1丁目7-11</td></tr>
                            </table>
                        """.trimIndent()
                        else -> "<html></html>"
                    }
                    LoadedDenominationChurchPage(url, html, "2026-07-23T00:00:00Z", cacheHit = false)
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"))

            assertEquals(crawler.sourceUrls, loadedUrls.take(crawler.sourceUrls.size))
            assertEquals("https://j-ag.org/church/takasu/", loadedUrls.last())
            assertEquals(25, result.pageCount)
            assertEquals("鷹栖キリスト教会", result.list.churches.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runnerWritesJaccPerDenominationJson() {
        val root = Files.createTempDirectory("crossmap-jacc-list")
        try {
            val crawler = JACCDenominationChurchListCrawler()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { _, _ ->
                    LoadedDenominationChurchPage(
                        crawler.sourceUrl,
                        """
                            <table border="1">
                              <tr bgcolor="#FFFF99">
                                <td><a href="db_disp.php?recordID=test"><strong>	  新札幌聖書教会</strong></a></td>
                                <td><strong>朴 永基</strong></td>
                                <td><strong>〒004-0033 北海道<a href="http://maps.google.co.jp/maps?q=test">北海道札幌市厚別区上野幌三条6-13-15</a></strong></td>
                              </tr>
                              <tr>
                                <td>[101・1]北海道宣教区</td>
                                <td></td>
                                <td>電話：[011(892)5233]　FAX：[011(892)5274]</td>
                              </tr>
                              <tr>
                                <td>礼拝：10:30</td>
                                <td></td>
                                <td>e-Mail：</td>
                              </tr>
                              <tr>
                                <td>祈祷会：(水)10:30</td>
                                <td></td>
                                <td><a href="https://test.domei.church/" target="_blank">https://test.domei.church/</a></td>
                              </tr>
                              <tr>
                                <td colspan="3">★</td>
                              </tr>
                            </table>
                        """.trimIndent(),
                        "2026-07-19T00:00:00Z",
                        cacheHit = false,
                    )
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"), forceRefresh = true)

            assertEquals(1, result.list.churches.size)
            assertFalse(result.cacheHit)
            val written = json.decodeFromString<OfficialDenominationChurchList>(
                Files.readString(root.resolve("crawl/jacc-churches.json")),
            )
            assertEquals("JACC", written.denominationId)
            assertEquals("新札幌聖書教会", written.churches.single().name)
            assertEquals("[101・1]北海道宣教区", written.churches.single().jurisdiction)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runnerWritesTypedPerDenominationJson() {
        val root = Files.createTempDirectory("crossmap-uccj-list")
        try {
            val crawler = UCCJDenominationChurchListCrawler()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { _, _ ->
                    LoadedDenominationChurchPage(
                        crawler.sourceUrl,
                        "<table class='kyokai'><tr><td class='name'>修善寺教会</td><td class='kyouku'>東海</td><td class='postno'>410-2407</td><td class='address'>伊豆市柏久保455-1</td><td class='tel'></td><td class='fax'></td></tr></table>",
                        "2026-07-18T00:00:00Z",
                        cacheHit = false,
                    )
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"), forceRefresh = true)

            assertEquals(1, result.list.churches.size)
            assertFalse(result.cacheHit)
            val written = json.decodeFromString<OfficialDenominationChurchList>(
                Files.readString(root.resolve("crawl/uccj-churches.json")),
            )
            assertEquals("UCCJ", written.denominationId)
            assertEquals("修善寺教会", written.churches.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runnerWritesJhcPerDenominationJson() {
        val root = Files.createTempDirectory("crossmap-jhc-list")
        try {
            val crawler = JHCDenominationChurchListCrawler()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { _, _ ->
                    LoadedDenominationChurchPage(
                        crawler.sourceUrl,
                        """
                            <table>
                              <tr style="background-color: #c200c2">
                                <td>ブロック</td><td>教区</td><td>教会</td><td>郵便番号</td><td>住所</td><td>電話</td><td>FAX</td><td>備考</td>
                              </tr>
                              <tr style="background-color: #000000">
                                <td>関東</td><td>東京教区</td><td>東京ホーリネス教会</td><td>113-0032</td><td>東京都文京区弥生1-1-1</td><td>03-3811-1234</td><td>03-3811-1235</td><td></td>
                              </tr>
                            </table>
                        """.trimIndent(),
                        "2026-07-19T00:00:00Z",
                        cacheHit = false,
                    )
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"), forceRefresh = true)

            assertEquals(1, result.list.churches.size)
            assertFalse(result.cacheHit)
            val written = json.decodeFromString<OfficialDenominationChurchList>(
                Files.readString(root.resolve("crawl/jhc-churches.json")),
            )
            assertEquals("JHC", written.denominationId)
            assertEquals("東京ホーリネス教会", written.churches.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runnerWritesRcjPerDenominationJson() {
        val root = Files.createTempDirectory("crossmap-rcj-list")
        try {
            val crawler = RCJDenominationChurchListCrawler()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { _, _ ->
                    LoadedDenominationChurchPage(
                        crawler.sourceUrl,
                        """
                            <div class="list_area">
                              <section>
                                <h3><a href="detail.php?id=1">札幌キリスト改革派教会</a></h3>
                                <h4>〒060-0061 北海道札幌市中央区北一条西4-1-2</h4>
                              </section>
                            </div>
                        """.trimIndent(),
                        "2026-07-19T00:00:00Z",
                        cacheHit = false,
                    )
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"), forceRefresh = true)

            assertEquals(1, result.list.churches.size)
            assertFalse(result.cacheHit)
            val written = json.decodeFromString<OfficialDenominationChurchList>(
                Files.readString(root.resolve("crawl/rcj-churches.json")),
            )
            assertEquals("RCJ", written.denominationId)
            assertEquals("札幌キリスト改革派教会", written.churches.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun runnerWritesIgmPerDenominationJson() {
        val root = Files.createTempDirectory("crossmap-igm-list")
        try {
            val crawler = IGMDenominationChurchListCrawler()
            val runner = DenominationChurchListCrawlerRunner(
                pageLoader = { _, _ ->
                    LoadedDenominationChurchPage(
                        crawler.sourceUrl,
                        """
                            <table>
                              <tr><th><a href="https://example.com/tokyo">東京中野教会</a></th>
                                <td>牧師：田中 太郎<br>〒165-0023 東京都中野区中野5-30-10<br>TEL 03-3368-1234</td></tr>
                            </table>
                        """.trimIndent(),
                        "2026-07-19T00:00:00Z",
                        cacheHit = false,
                    )
                },
                json = json,
            )

            val result = runner.crawl(crawler, root, root.resolve("cache"), forceRefresh = true)

            assertEquals(1, result.list.churches.size)
            assertFalse(result.cacheHit)
            val written = json.decodeFromString<OfficialDenominationChurchList>(
                Files.readString(root.resolve("crawl/igm-churches.json")),
            )
            assertEquals("IGM", written.denominationId)
            assertEquals("東京中野教会", written.churches.single().name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun authoritativeListsCorrectProgrammaticLabelsButPreserveHumanReview() {
        val root = Files.createTempDirectory("crossmap-denomination-reconcile")
        try {
            Files.createDirectories(root.resolve("catalog"))
            val catalog = listOf(
                church("google:906297735827744432", "岡山バプテスト教会", "〒700-0825 岡山県岡山市北区田町１丁目７−２８", "JBC"),
                church("google:10003118417314172796", "日本基督教団 八頭教会", "〒680-0463 鳥取県八頭郡八頭町宮谷２２２", NOT_DETERMINED),
                church("google:3006657650131411393", "イエス愛の教会", "〒417-0073 静岡県富士市浅間本町１", "JBC", "https://www.facebook.com/fujijesuslove/"),
                church("google:10287390816407389399", "沼津キリストの教会", "〒410-0853 静岡県沼津市常盤町２丁目３", "JBC", "https://www.facebook.com/profile.php?id=100064332113625"),
                church("google:human", "人手確認済みバプテスト教会", "〒100-0001 東京都千代田区千代田１", "JBC").copy(
                    determinations = listOf(FieldDetermination("denominationId", "JBC", DeterminationSource.HUMAN, 1.0)),
                ),
            )
            val catalogFile = root.resolve("catalog/churches.json")
            Files.writeString(catalogFile, json.encodeToString(catalog))
            val lists = listOf(
                officialList("JBC", listOf(OfficialDenominationChurch("岡山バプテスト教会", "〒700-0825 岡山県岡山市北区田町1丁目7-28"))),
                officialList(
                    "UCCJ",
                    listOf(
                        OfficialDenominationChurch("八頭教会", "〒680-0463 鳥取県八頭郡八頭町宮谷222"),
                        OfficialDenominationChurch("未登録公式教会", "〒100-0002 東京都千代田区皇居外苑1"),
                    ),
                ),
            )

            val report = OfficialDenominationChurchListReconciler(json = json).reconcile(
                catalogFile,
                lists,
                googlePlaceTitlesByChurchId = mapOf(
                    "google:10003118417314172796" to "Google Maps 日本基督教団 八頭教会",
                ),
            )
            val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile)).associateBy(ChurchRecord::id)

            assertEquals("JBC", updated.getValue("google:906297735827744432").denominationId)
            assertEquals("UCCJ", updated.getValue("google:10003118417314172796").denominationId)
            assertEquals(NOT_DETERMINED, updated.getValue("google:3006657650131411393").denominationId)
            assertEquals(NOT_DETERMINED, updated.getValue("google:10287390816407389399").denominationId)
            assertEquals("JBC", updated.getValue("google:human").denominationId)
            assertEquals(1, report.assigned)
            assertEquals(2, report.removedUnsupportedLabels)
            assertEquals(1, report.humanOverridesPreserved)
            assertEquals(1, report.unmatchedOfficialEntries)
            val auditLog = report.toHumanReadableAuditLog()
            assertTrue(auditLog.contains("denominations_assigned (1)"))
            assertTrue(auditLog.contains("performed opperation: denominations_assigned"))
            assertTrue(auditLog.contains("google place title: Google Maps 日本基督教団 八頭教会"))
            assertTrue(auditLog.contains("unsupported_labels_removed (2)"))
            assertTrue(auditLog.contains("data from denomination crawler: no matching official church"))
            assertTrue(auditLog.contains("unmatched_official_entries (1)"))
            assertTrue(auditLog.contains("church name: 未登録公式教会"))
            assertTrue(auditLog.contains("data from google map saved place: no matching church"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun oneOfficialRowCanAuthorizeOnlyOneCanonicalChurchRecord() {
        val root = Files.createTempDirectory("crossmap-denomination-one-to-one")
        try {
            Files.createDirectories(root.resolve("catalog"))
            val exact = church("google:1", "岡山バプテスト教会", "〒700-0825 岡山県岡山市北区田町１丁目７−２８", "JBC")
            val staleDuplicate = church("google:2", "岡山バプテスト教会別館", "〒700-0826 岡山県岡山市北区磨屋町", "JBC")
            val catalogFile = root.resolve("catalog/churches.json")
            Files.writeString(catalogFile, json.encodeToString(listOf(exact, staleDuplicate)))

            OfficialDenominationChurchListReconciler(json = json).reconcile(
                catalogFile,
                listOf(officialList("JBC", listOf(OfficialDenominationChurch("岡山バプテスト教会", exact.address)))),
            )
            val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile)).associateBy(ChurchRecord::id)

            assertEquals("JBC", updated.getValue("google:1").denominationId)
            assertEquals(NOT_DETERMINED, updated.getValue("google:2").denominationId)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun officialLocalizedNameReplacesGeneratedEnglishNameInCanonicalRecord() {
        val root = Files.createTempDirectory("crossmap-denomination-official-name")
        try {
            Files.createDirectories(root.resolve("catalog"))
            val original = church("google:3", "コザ・バプテスト教会", "〒904-0004 沖縄県沖縄市中央１−１", "OBC")
                .copy(
                    englishName = "Generated Koza Baptist Church",
                    localizedNames = listOf(LocalizedName("en", "Generated Koza Baptist Church"), LocalizedName("ko", "고자 교회")),
                )
            val catalogFile = root.resolve("catalog/churches.json")
            Files.writeString(catalogFile, json.encodeToString(listOf(original)))

            OfficialDenominationChurchListReconciler(json = json).reconcile(
                catalogFile,
                listOf(
                    officialList(
                        "OBC",
                        listOf(
                            OfficialDenominationChurch(
                                name = original.name,
                                localizedNames = listOf(LocalizedName("en", "Koza Baptist Church")),
                                address = original.address,
                            ),
                        ),
                    ),
                ),
            )

            val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile)).single()
            assertEquals("Koza Baptist Church", updated.englishName)
            assertEquals("Koza Baptist Church", updated.localizedNames.single { it.languageCode == "en" }.name)
            assertEquals("고자 교회", updated.localizedNames.single { it.languageCode == "ko" }.name)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun publishedCatalogDoesNotLabelTheTwoRealFalsePositivesAsJbc() {
        val resources = sequenceOf(java.nio.file.Path.of("resources"), java.nio.file.Path.of("../resources"))
            .first { Files.isRegularFile(it.resolve("catalog/churches.json")) }
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(resources.resolve("catalog/churches.json")))
        val byWebPresence = churches.flatMap { church ->
            (listOf(church.websiteUrl) + church.socialProfiles.map { it.url })
                .filter(String::isNotBlank)
                .map { it to church }
        }.toMap()

        assertNotEquals("JBC", byWebPresence.getValue("https://facebook.com/fujijesuslove").denominationId)
        assertNotEquals("JBC", byWebPresence.getValue("https://facebook.com/profile.php?id=100064332113625").denominationId)
    }

    private fun officialList(id: String, churches: List<OfficialDenominationChurch>) = OfficialDenominationChurchList(
        denominationId = id,
        denominationName = when (id) {
            "JBC" -> "日本バプテスト連盟"
            "OBC" -> "沖縄バプテスト連盟"
            else -> "日本基督教団"
        },
        sourceUrl = if (id == "JBC") "https://bapren.jp/church/" else "https://uccj.org/diocese",
        fetchedAt = "2026-07-18T00:00:00Z",
        churches = churches,
    )

    private fun church(
        id: String,
        name: String,
        address: String,
        denominationId: String,
        websiteUrl: String = "https://www.google.com/maps?cid=${id.substringAfter(':')}",
    ) = ChurchRecord(
        id = id,
        googleCid = id.substringAfter(':').takeIf { it.all(Char::isDigit) },
        name = name,
        englishName = "Test Church ${id.substringAfter(':')}",
        denominationId = denominationId,
        address = address,
        location = GeoPoint(35.0, 135.0),
        websiteUrl = websiteUrl,
    )
}

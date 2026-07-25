package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class CatholicDioceseChurchListCrawlerTest {
    @Test
    fun nagoyaMapsBlockAndDetailLink() {
        val crawler = CatholicNagoyaDioceseChurchListCrawler(listOf("https://nagoya.catholic.jp/church"))
        val church = crawler.parse("""
            <div class='church-block'><h4 class='block-title'>城北ブロック</h4><div class='list-wrapper'>
              <a href='/church/gotanjo/'>カトリック五反城教会</a>
            </div></div>
        """).single()
        assertEquals("名古屋教区・城北ブロック", church.jurisdiction)
        assertEquals("https://nagoya.catholic.jp/church/gotanjo/", church.denominationChurchListDetailPage)
    }

    @Test
    fun osakaTakamatsuMapsEachPrefectureTable() {
        val crawler = CatholicOsakaTakamatsuArchdioceseChurchListCrawler(listOf("https://ostk.catholic.jp/parish_mass/"))
        val church = crawler.parse("""
            <div><h3>大阪府</h3><figure class='parish_list'><table><tbody><tr>
              <td><a href='https://abeno.example/'>阿倍野教会</a></td>
              <td>545-0053<br>大阪市阿倍野区松崎町3-6-25<br>06-6621-6024</td>
            </tr></tbody></table></figure></div>
        """).single()
        assertEquals("大阪高松大司教区・大阪府", church.jurisdiction)
        assertEquals("〒545-0053 大阪市阿倍野区松崎町３−６−２５", church.address)
        assertEquals("06-6621-6024", church.phone)
        assertEquals("https://abeno.example/", church.websiteUrl)
    }

    @Test
    fun hiroshimaMapsNumberedChurchWithinPrefecture() {
        val crawler = CatholicHiroshimaDioceseChurchListCrawler(listOf("https://hiroshima.catholic.jp/church/"))
        val church = crawler.parse("""
            <h3>山口県</h3><ul><li class='kyoudoutai'><a href='./iwakuni/'>01 岩国教会</a></li></ul>
        """).single()
        assertEquals("岩国教会", church.name)
        assertEquals("広島教区・山口県", church.jurisdiction)
    }

    @Test
    fun fukuokaMapsCardContactColumns() {
        val crawler = CatholicFukuokaDioceseChurchListCrawler(listOf("https://fukuoka.catholic.jp/parish/"))
        val church = crawler.parse("""
            <div class='fuk'><div class='col_2'><table>
              <tr><th>教会名</th><td><a title='老司教会/ROUJI' href='/parish/rouji/'>老司教会/ROUJI</a></td></tr>
              <tr><th>住所</th><td>〒811-1346 福岡市南区老司5-28-1</td></tr>
              <tr><th>TEL / FAX</th><td>092-566-2323 / 092-566-2324</td></tr>
            </table></div></div>
        """).single()
        assertEquals("福岡教区・福岡", church.jurisdiction)
        assertEquals("092-566-2323", church.phone)
        assertEquals("092-566-2324", church.fax)
    }

    @Test
    fun nagasakiMapsRowsAndExcludesMeetingPlaces() {
        val crawler = CatholicNagasakiArchdioceseChurchListCrawler(listOf("https://www.nagasaki.catholic.jp/information/address"))
        val churches = crawler.parse("""
            <div class='entry-content'><table><tbody>
              <tr><td><b>飽の浦</b>◆聖ヨセフ</td><td>850-0063 長崎市飽の浦町8-50 095-861-2589</td></tr>
              <tr><td>（集会所）福田</td><td>850-0067 長崎市小浦町59-2</td></tr>
            </tbody></table></div>
        """)
        assertEquals(listOf("カトリック飽の浦教会"), churches.map { it.name })
        assertEquals("長崎大司教区・第1地区", churches.single().jurisdiction)
    }

    @Test
    fun kagoshimaMapsDistrictFromDetailUrl() {
        val crawler = CatholicKagoshimaDioceseChurchListCrawler(listOf("https://kagoshima-catholic.jp/diocese/"))
        val church = crawler.parse("<h2><a href='/diocese/amami/508.html/'>大笠利教会</a></h2>").single()
        assertEquals("鹿児島教区・奄美地区", church.jurisdiction)
    }

    @Test
    fun nahaMapsElementorChurchContactCard() {
        val crawler = CatholicNahaDioceseChurchListCrawler(listOf("https://www.naha.catholic.jp/wp/wordpress/parish/"))
        val church = crawler.parse("""
            <div><div class='elementor-widget-heading'><h2>本島北部</h2></div></div>
            <div class='e-con'><div class='elementor-widget-heading'><h2>カトリック名護教会</h2></div>
              <div class='elementor-widget-text-editor'><p>住所：〒905-0018 沖縄県名護市大西2-1-20<br>
              TEL : 098-052-2241 FAX : 098-054-2854<br>主任司祭：マイケル ヴィン神父</p></div>
            </div>
        """).single()
        assertEquals("那覇教区・本島北部", church.jurisdiction)
        assertEquals("098-052-2241", church.phone)
        assertEquals("マイケル ヴィン", church.ministers.single().name)
    }

    @Test
    fun saitamaMapsArchivedDatabaseCard() {
        val crawler = CatholicSaitamaDioceseChurchListCrawler(listOf("https://web.archive.org/example/our_parishes/"))
        val church = crawler.parse("""
            <table width='680' bgcolor='#ececec'><tr><td><font style='font-size:130%;'><b>川口教会</b></font></td></tr>
              <tr><td><img src='image/icon-church.png'>〒332-0012 埼玉県川口市本町2-4-15 TEL：048-222-3588 FAX：048-224-1431</td></tr>
            </table>
        """).single()
        assertEquals("さいたま教区・埼玉県", church.jurisdiction)
        assertEquals("048-222-3588", church.phone)
    }

    @Test
    fun yokohamaMapsConsecutiveHeadingAndDetailBlocks() {
        val crawler = CatholicYokohamaDioceseChurchListCrawler(listOf("http://www.yokohama.catholic.jp/syokyoku_top/yb_parish_k01.html"))
        val church = crawler.parse("""
            <div id='church' class='block'><span class='fsize_ll'>カトリック貝塚教会</span></div>
            <div class='block'>主任司祭 山口 道孝 〒210-0014 川崎市川崎区貝塚1-8-9 TEL044-222-3075 FAX044-222-2843</div>
            <div class='block'><span class='fsize_ll'>カトリック浅田教会</span></div>
        """).first()
        assertEquals("横浜教区・神奈川県", church.jurisdiction)
        assertEquals("044-222-3075", church.phone)
    }

    @Test
    fun kyotoMapsSevenColumnDirectoryRows() {
        val crawler = CatholicKyotoDioceseChurchListCrawler(listOf("https://kyoto.catholic.jp/addres/Address_Table.htm"))
        val church = crawler.parse("""
            <table><tr><td>京都府</td></tr><tr>
              <td><a href='https://kawaramachi.example/'>河原町 &lt;司教座聖堂&gt; KAWARAMACHI</a></td><td>日曜</td><td>平日</td>
              <td>604-8006 京都市中京区河原町通三条上ル</td><td>075-231-4785</td><td>075-211-8021</td><td>MAP</td>
            </tr></table>
        """).single()
        assertEquals("カトリック河原町教会", church.name)
        assertEquals("京都教区・京都府", church.jurisdiction)
    }

    @Test
    fun oitaMapsRecordCardsByPagePrefecture() {
        val crawler = CatholicOitaDioceseChurchListCrawler(listOf("https://oita-catholic.jp/pages/45/"))
        val church = crawler.parse("""
            <section class='record' role='listitem'><div class='text'>由布教会 〒879-5102 由布市湯布院町川上451-12
              TEL 097-532-2452 FAX 097-532-2405</div></section>
        """).single()
        assertEquals("由布教会", church.name)
        assertEquals("大分教区・大分県", church.jurisdiction)
    }
}

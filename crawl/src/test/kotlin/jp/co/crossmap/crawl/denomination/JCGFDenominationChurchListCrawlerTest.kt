package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class JCGFDenominationChurchListCrawlerTest {
    private val crawler = JCGFDenominationChurchListCrawler(
        "http://xn--u9j463geip7pa94cc38by5dpv1d.com/",
    )

    @Test
    fun parsesOnlyOfficialChurchDetailLinksAndNormalizesHalfwidthKana() {
        val churches = crawler.parse(
            """
            <a href="/kaminokyoukaitowa.html"><img alt="神の教会とは"></a>
            <a href="/kakukyoukai/oshirase.html"><img alt="お知らせ"></a>
            <a href="/kakukyoukai/sorachi.html">空知太栄光キリスト教会</a>
            <a href="/kakukyoukai/kiyose.html">清瀬旭ｹ丘教会</a>
            <a href="/kakukyoukai/tomishiro.html">豊見城神の教会（沖縄ｺﾞｽﾍﾟﾙﾌｧﾐﾘｰﾁｬｰﾁ）</a>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "空知太栄光キリスト教会",
                "清瀬旭ケ丘教会",
                "豊見城神の教会(沖縄ゴスペルファミリーチャーチ)",
            ),
            churches.map { it.name },
        )
        assertEquals(
            "http://xn--u9j463geip7pa94cc38by5dpv1d.com/kakukyoukai/sorachi.html",
            churches.first().denominationChurchListDetailPage,
        )
    }

    @Test
    fun enrichesChurchFromTheOfficialDetailPage() {
        val church = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "大阪鴻池神の教会",
                denominationChurchListDetailPage =
                    "http://xn--u9j463geip7pa94cc38by5dpv1d.com/kakukyoukai/kounoike.html",
            ),
            """
            <table>
              <tr>
                <td>所在地：〒５７８－０９７２ 大阪府東大阪市鴻池町１－３０－３８</td>
                <td>植 松 光 春 牧師</td>
              </tr>
              <tr><td colspan="2">電 話 ： ０７２－８７２－５８１４</td></tr>
              <tr><td colspan="2">メール：okg-church@live.jp</td></tr>
              <tr><td colspan="2">ホームページ：<a href="https://www.okg-church.com/">教会サイト</a></td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals("〒578-0972 大阪府東大阪市鴻池町１−３０−３８", church.address)
        assertEquals("大阪府", church.jurisdiction)
        assertEquals("072-872-5814", church.phone)
        assertEquals("okg-church@live.jp", church.email)
        assertEquals("https://www.okg-church.com/", church.websiteUrl)
        assertEquals(listOf("植松光春"), church.ministers.map { it.name })
        assertEquals(listOf("pastor"), church.ministers.map { it.roleId })
    }

    @Test
    fun parsesAnAddressWithoutPostalCodeAndCombinedTelephoneFaxLabel() {
        val church = crawler.parseDetailPage(
            OfficialDenominationChurch(
                name = "豊見城神の教会(沖縄ゴスペルファミリーチャーチ)",
                denominationChurchListDetailPage =
                    "http://xn--u9j463geip7pa94cc38by5dpv1d.com/kakukyoukai/tomishiro.html",
            ),
            """
            <table>
              <tr><td>所在地：沖縄県豊見城市字饒波４１０－１</td></tr>
              <tr><td>電 話/FAX：０９８－８５０－６６５７</td></tr>
            </table>
            """.trimIndent(),
        )

        assertEquals("沖縄県豊見城市字饒波４１０−１", church.address)
        assertEquals("沖縄県", church.jurisdiction)
        assertEquals("098-850-6657", church.phone)
        assertEquals("098-850-6657", church.fax)
    }

    @Test
    fun keepsSeparateMinisterCellsAndHandlesTheLegacyTwoNameRoleOrder() {
        val separate = crawler.parseDetailPage(
            OfficialDenominationChurch("空知太栄光キリスト教会"),
            """
            <table><tr>
              <td>所在地：〒073-0175 北海道砂川市空知太西５条７丁目１－２４</td>
              <td>銘形秀則 主任牧師</td>
              <td>神田満 牧師</td>
            </tr></table>
            """.trimIndent(),
        )
        assertEquals(
            listOf("銘形秀則" to "senior_pastor", "神田満" to "pastor"),
            separate.ministers.map { it.name to it.roleId },
        )

        val paired = crawler.parseDetailPage(
            OfficialDenominationChurch("沖縄天久神の教会"),
            """
            <table><tr>
              <td>所在地：〒900-0005 沖縄県那覇市天久８３３－１</td>
              <td>喜瀬英之 折田政博 牧師 名誉牧師</td>
            </tr></table>
            """.trimIndent(),
        )
        assertEquals(
            listOf("喜瀬英之" to "pastor", "折田政博" to "pastor_emeritus"),
            paired.ministers.map { it.name to it.roleId },
        )
    }
}

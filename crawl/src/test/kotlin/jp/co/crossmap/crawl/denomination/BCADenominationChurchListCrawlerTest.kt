package jp.co.crossmap.crawl.denomination

import kotlin.test.Test
import kotlin.test.assertEquals

class BCADenominationChurchListCrawlerTest {
    private val crawler = BCADenominationChurchListCrawler(
        "https://church.ne.jp/bethany/assemblies.html",
    )

    @Test
    fun parsesLegacyChurchSectionsAndRepairsKnownAddressDefects() {
        val churches = crawler.parse(
            """
            <h6>ベタニヤチャペル</h6>
            <h4>牧師：マーク・マグヌソン先生</h4>
            <p>〒４７０－０１３１ 日進市岩崎町石兼３６－３</p>
            <p>℡ 0561(72)1166／（73）5323 FAX0561(73)9040</p>
            <h6>豊田ホープチャペル</h6>
            <p><a href="http://toyotahopechapel.jp/">ホームページ</a></p>
            <h4>牧師： 山本孝次先生</h4>
            <h4>副牧師：山本愛先生</h4>
            <p>〒４７１－００１３ 豊田市高上1丁目8番地１２</p>
            <p>℡&FAX 0565(80)7520 E-mail: toyotahopechapel@gmail.com</p>
            <h6>保見キリスト教会</h6>
            <h4>牧師： 高橋吉晴先生 ／ 高橋恵子先生</h4>
            <p>〒４０７－０３５５ 豊田市保見ヶ丘1丁目78番地</p>
            <p>℡&FAX 0565(48)3562</p>
            """.trimIndent(),
        )

        assertEquals(listOf("ベタニヤチャペル", "豊田ホープチャペル", "保見キリスト教会"), churches.map { it.name })
        assertEquals("〒470-0131 愛知県日進市岩崎町石兼３６−３", churches[0].address)
        assertEquals("0561-72-1166", churches[0].phone)
        assertEquals("0561-73-9040", churches[0].fax)
        assertEquals(listOf("マーク・マグヌソン"), churches[0].ministers.map { it.name })
        assertEquals("0565-80-7520", churches[1].fax)
        assertEquals("toyotahopechapel@gmail.com", churches[1].email)
        assertEquals(
            listOf("山本孝次" to "pastor", "山本愛" to "associate_pastor"),
            churches[1].ministers.map { it.name to it.roleId },
        )
        assertEquals("〒470-0355 愛知県豊田市保見ヶ丘１丁目７８番地", churches[2].address)
    }
}

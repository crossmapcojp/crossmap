package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals

class ChurchPublicNameNormalizerTest {
    @Test
    fun removesReligiousCorporationMarkersAndAdjacentEdgeSymbols() {
        val examples = listOf(
            "宗教法人滝山聖書バプテスト教会",
            "宗教法人 滝山聖書バプテスト教会",
            "宗教法人　滝山聖書バプテスト教会",
            "（宗教法人）滝山聖書バプテスト教会",
            "(宗教法人) 滝山聖書バプテスト教会",
            "（宗）滝山聖書バプテスト教会",
            "(宗) 滝山聖書バプテスト教会",
            "宗教法人/滝山聖書バプテスト教会",
            "宗教法人／滝山聖書バプテスト教会",
            "宗教法人・滝山聖書バプテスト教会",
            "宗教法人|滝山聖書バプテスト教会",
            "宗教法人｜滝山聖書バプテスト教会",
            "宗教法人：滝山聖書バプテスト教会",
            "滝山聖書バプテスト教会・宗教法人",
            "滝山聖書バプテスト教会 / (宗)",
            "【宗教法人】滝山聖書バプテスト教会",
            "INDEPENDENT_CHURCH 滝山聖書バプテスト教会",
        )

        examples.forEach { source ->
            assertEquals("滝山聖書バプテスト教会", ChurchPublicNameNormalizer.normalize(source), source)
        }
    }
}

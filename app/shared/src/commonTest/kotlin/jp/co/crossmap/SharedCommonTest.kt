package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {
    @Test
    fun realChurchDetailUsesCanonicalFields() {
        val detail = ChurchDetailResponse(
            indexVersion = "initial-2025-02-22",
            churchId = "google:906297735827744432",
            name = "岡山バプテスト教会",
            englishName = "Okayama Baptist Church",
            denominationId = "JBC",
            address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
            location = GeoPoint(34.6619806, 133.9231824),
            websiteUrl = "http://okayama-baptist.jp/",
        )
        assertEquals("JBC", detail.denominationId)
        assertEquals("〒700-0825 岡山県岡山市北区田町１丁目７−２８", detail.address)
    }
}

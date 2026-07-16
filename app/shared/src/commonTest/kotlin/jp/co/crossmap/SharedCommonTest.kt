package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedCommonTest {
    @Test
    fun preferredLanguageSelectsLocalizedChurchNameAndFallsBackSafely() {
        val names = listOf(
            LocalizedName("ja", "日本基督教団赤羽教会"),
            LocalizedName("ko", "일본기독교단 아카바네 교회"),
        )

        assertEquals(
            "일본기독교단 아카바네 교회",
            preferredChurchName(names, "ko", "日本基督教団赤羽教会", "UCCJ Akabane Church"),
        )
        assertEquals(
            "UCCJ Akabane Church",
            preferredChurchName(names, "en", "日本基督教団赤羽教会", "UCCJ Akabane Church"),
        )
        assertEquals(
            "日本基督教団赤羽教会",
            preferredChurchName(names, "pt", "日本基督教団赤羽教会", "UCCJ Akabane Church"),
        )
    }

    @Test
    fun preferredLanguageSelectsLocalizedDenominationNameAndFallsBackToId() {
        val names = listOf(
            LocalizedName("ja", "日本基督教団"),
            LocalizedName("ko", "일본기독교단"),
        )

        assertEquals("일본기독교단", preferredDenominationName(names, "ko", "UCCJ"))
        assertEquals("UCCJ", preferredDenominationName(names, "pt", "UCCJ"))
    }

    @Test
    fun realChurchDetailUsesCanonicalFields() {
        val detail = ChurchDetailResponse(
            indexVersion = "initial-2025-02-22",
            churchId = "google:906297735827744432",
            name = "岡山バプテスト教会",
            englishName = "Okayama Baptist Church",
            denominationId = "JBC",
            localizedDenominationNames = listOf(LocalizedName("ja", "日本バプテスト連盟")),
            address = "〒700-0825 岡山県岡山市北区田町１丁目７−２８",
            location = GeoPoint(34.6619806, 133.9231824),
            websiteUrl = "http://okayama-baptist.jp/",
        )
        assertEquals("JBC", detail.denominationId)
        assertEquals("日本バプテスト連盟", detail.localizedDenominationNames.single().name)
        assertEquals("〒700-0825 岡山県岡山市北区田町１丁目７−２８", detail.address)
    }
}

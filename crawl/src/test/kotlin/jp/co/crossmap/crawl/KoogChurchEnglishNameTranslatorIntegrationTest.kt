package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class KoogChurchEnglishNameTranslatorIntegrationTest {
    @Test
    fun catTranslatesRealTokyoSophiaChurchNameThroughKoogAndOllama() = runBlocking {
        if (System.getenv("CROSSMAP_OLLAMA_INTEGRATION") != "1") return@runBlocking

        val result = KoogChurchEnglishNameTranslator().translate(
            ChurchEnglishNameInput(
                id = "official:tokyo-sophia",
                name = "東京ソフィア長老教会",
                address = "東京都新宿区西早稲田",
                location = GeoPoint(35.708, 139.709),
                websiteUrl = "https://olivetassembly.or.jp/our-regions.html",
            ),
        )

        assertEquals("Tokyo Sophia Presbyterian Church", result.englishName)
        assertEquals(CAT_TRANSLATE_MODEL, result.model)
        assertTrue(result.parts.any { it.role == ChurchNamePartRole.TRADITION && it.english == "Presbyterian" })
        assertTrue(result.parts.any { it.role == ChurchNamePartRole.CONGREGATION && it.english == "Church" })
    }

    @Test
    fun catUsesRealSaintLuciaUrlSpellingInsteadOfKanaOnlyRomanization() = runBlocking {
        if (System.getenv("CROSSMAP_OLLAMA_INTEGRATION") != "1") return@runBlocking

        val result = KoogChurchEnglishNameTranslator().translate(
            ChurchEnglishNameInput(
                id = "google:9753525676873678048",
                name = "日本聖公会聖ルシヤ教会",
                denominationId = "ANGLICAN_JP",
                address = "〒584-0074 大阪府富田林市久野喜台２丁目１５−１",
                location = GeoPoint(34.4991617, 135.5613646),
                websiteUrl = "http://www.nskk.org/osaka/church/lucia/",
            ),
        )

        assertEquals("St. Lucia Church", result.englishName)
    }

    @Test
    fun reconstructionKeepsRealKokoronotomoDomainProperName() = runBlocking {
        if (System.getenv("CROSSMAP_OLLAMA_INTEGRATION") != "1") return@runBlocking

        val result = KoogChurchEnglishNameTranslator().translate(
            ChurchEnglishNameInput(
                id = "google:13045351237600372838",
                name = "心の友キリスト教会",
                denominationId = "INDEPENDENT_CHURCH",
                address = "〒227-0036 神奈川県横浜市青葉区奈良町１５６６−２９",
                location = GeoPoint(35.5621046, 139.4792824),
                websiteUrl = "https://kokoronotomo-ch.org/",
            ),
        )

        assertEquals("Kokoronotomo Church", result.englishName)
    }
}

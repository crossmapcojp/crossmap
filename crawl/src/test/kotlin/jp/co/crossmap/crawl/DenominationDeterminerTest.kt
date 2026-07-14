package jp.co.crossmap.crawl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DenominationDeterminerTest {
    @Test
    fun programmaticDeterminationUsesDataRulesAndRejectsAmbiguity() {
        val rules = listOf(
            DenominationRule("UCCJ", "日本基督教団", churchNameComponents = listOf("日本キリスト教団"), source = "crossmap/UCCJ"),
            DenominationRule("JBC", "日本バプテスト連盟", churchNameComponents = listOf("バプテスト連盟"), source = "crossmap/JBC"),
        )
        val determiner = ProgrammaticDenominationDeterminer(rules)

        val result = determiner.determineDenominationProgrammatically("日本基督教団 八頭教会")

        assertEquals("UCCJ", result.denomination.id)
        assertTrue(result.score >= 0.9f)
        assertEquals(NOT_DETERMINED, determiner.determineDenominationProgrammatically("栗山地の塩キリスト教会").denomination.id)
    }
}

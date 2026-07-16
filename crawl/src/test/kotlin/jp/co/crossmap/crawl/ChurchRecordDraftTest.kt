package jp.co.crossmap.crawl

import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChurchRecordDraftTest {
    @Test
    fun generatedEnglishNameIsRecomputedButHumanReviewIsPreserved() {
        val generated = draft(DeterminationSource.LLM)
        val reviewed = draft(DeterminationSource.HUMAN)

        assertNull(generated.toEnglishNameInput().existingEnglishName)
        assertEquals("Reviewed Church", reviewed.toEnglishNameInput().existingEnglishName)
    }

    @Test
    fun preservesLocalizedNamesAndOriginalTitleLanguagesDuringPublication() {
        val draft = draft(DeterminationSource.PROGRAMMATIC).copy(
            localizedNames = listOf(
                LocalizedName("ja", "山梨再臨キリスト教会"),
                LocalizedName("es", "Iglesia Cristo Viene Yamanashi"),
                LocalizedName("en", "Yamanashi Christ Is Coming Church"),
            ),
            titleLanguages = listOf("es"),
        )

        val published = draft.toChurchRecord(
            ResolvedChurchEnglishName(
                englishName = "Yamanashi Christ Is Coming Church",
                source = DeterminationSource.PROGRAMMATIC,
                confidence = 0.99f,
                evidence = listOf("Deterministic component translation"),
            ),
            determinedAt = "2026-07-16T00:00:00Z",
        )

        assertEquals(draft.localizedNames, published.localizedNames)
        assertEquals(listOf("es"), published.titleLanguages)
    }

    private fun draft(source: DeterminationSource) = ChurchRecordDraft(
        id = "real:reviewed",
        name = "レビュー教会",
        englishName = "Reviewed Church",
        address = "東京都",
        location = GeoPoint(35.0, 139.0),
        websiteUrl = "",
        determinations = listOf(
            FieldDetermination(
                field = "englishName",
                value = "Reviewed Church",
                source = source,
                confidence = 1.0,
            ),
        ),
    )
}

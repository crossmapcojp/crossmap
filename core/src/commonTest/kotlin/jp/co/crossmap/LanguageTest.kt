package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanguageTest {
    @Test
    fun supportedLanguageCodesComeOnlyFromLanguageEnum() {
        assertEquals(listOf("ja", "en", "ko", "pt", "id"), supportedLanguageCodes)
        assertEquals(Language.JAPANESE, Language.fromCode("ja-JP"))
        assertEquals(Language.PORTUGUESE, Language.fromCode("pt_BR"))
        assertEquals(Language.ENGLISH, Language.fromCodeOrEnglish("fr-FR"))
    }

    @Test
    fun localizedTextRequiresEverySupportedLanguage() {
        assertFailsWith<IllegalArgumentException> {
            val constructor = LocalizedText::class
            @Suppress("UNUSED_VARIABLE")
            val keepReference = constructor
            LocalizedText.of("", "English", "한국어", "Português", "Indonesia")
        }
    }

    @Test
    fun everyTraditionHasEveryTranslation() {
        ChurchTradition.entries.forEach { tradition ->
            assertTrue(supportedLanguages.all { tradition.name(it).isNotBlank() }, tradition.name)
        }
        assertEquals("루터교", ChurchTradition.LUTHERAN.name(Language.KOREAN))
        assertEquals("루터", ChurchTradition.LUTHERAN.namePart(Language.KOREAN))
        assertEquals("Gereja Lutheran", ChurchTradition.LUTHERAN.name(Language.INDONESIAN))
    }

}

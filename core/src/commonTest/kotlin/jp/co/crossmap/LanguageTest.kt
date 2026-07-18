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

    @Test
    fun domainTextUsesRequestedEnglishJapaneseThenFirstNonblankFallback() {
        val names = listOf(
            LocalizedName("ja", "東京バプテスト教会"),
            LocalizedName("en", "Tokyo Baptist Church"),
            LocalizedName("ko", "도쿄 침례교회"),
        )
        assertEquals("도쿄 침례교회", localizedDomainText(Language.KOREAN, names))
        assertEquals("Tokyo Baptist Church", localizedDomainText(Language.PORTUGUESE, names))
        assertEquals(
            "東京バプテスト教会",
            localizedDomainText(Language.INDONESIAN, listOf(LocalizedName("ja", "東京バプテスト教会"))),
        )
        assertEquals("Primeiro nome", localizedDomainText(
            Language.INDONESIAN,
            listOf(LocalizedName("fr", "Primeiro nome")),
        ))
    }

}

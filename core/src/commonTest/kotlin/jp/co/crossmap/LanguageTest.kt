package jp.co.crossmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanguageTest {
    @Test
    fun parsesChineseScriptLocalesAndRegionalBrowserLocales() {
        assertEquals(Language.CHINESE_SIMPLIFIED, Language.fromCode("zh-Hans"))
        assertEquals(Language.CHINESE_SIMPLIFIED, Language.fromCode("zh_CN"))
        assertEquals(Language.CHINESE_SIMPLIFIED, Language.fromCode("zh-SG"))
        assertEquals(Language.CHINESE_SIMPLIFIED, Language.fromCode("zh"))
        assertEquals(Language.CHINESE_TRADITIONAL, Language.fromCode("zh-Hant"))
        assertEquals(Language.CHINESE_TRADITIONAL, Language.fromCode("zh-TW"))
        assertEquals(Language.CHINESE_TRADITIONAL, Language.fromCode("zh-HK"))
        assertEquals(Language.CHINESE_TRADITIONAL, Language.fromCode("zh-MO"))
    }

    @Test
    fun ChineseDisplayFallbackPrefersTheOtherStoredChineseScriptBeforeJapanese() {
        val names = listOf(
            LocalizedName("ja", "東京恵み教会"),
            LocalizedName("zh-Hant", "東京恩典教會"),
        )

        assertEquals(
            "東京恩典教會",
            localizedDomainText(Language.CHINESE_SIMPLIFIED, names),
        )
    }

    @Test
    fun ChineseDisplayPrefersReviewedStoredNameAndNeverUsesRejectedName() {
        fun metadata(
            source: LocalizedNameSource,
            reviewStatus: LocalizedNameReviewStatus,
            confidence: Double,
        ) = LocalizedNameMetadata(
            source = source,
            generationMethod = LocalizedNameGenerationMethod.SCRIPT_CONVERSION,
            confidence = confidence,
            reviewStatus = reviewStatus,
        )
        val names = listOf(
            LocalizedName("zh-Hans", "已拒绝的名称", metadata(LocalizedNameSource.GENERATED, LocalizedNameReviewStatus.REJECTED, 1.0)),
            LocalizedName("zh-Hans", "自动生成名称", metadata(LocalizedNameSource.GENERATED, LocalizedNameReviewStatus.UNREVIEWED, 0.99)),
            LocalizedName("zh-Hans", "人工审核名称", metadata(LocalizedNameSource.MANUAL, LocalizedNameReviewStatus.REVIEWED, 0.8)),
        )

        assertEquals("人工审核名称", localizedDomainText(Language.CHINESE_SIMPLIFIED, names))
    }

    @Test
    fun ChineseFallbackUsesJapaneseBeforeAnotherOfficialLanguage() {
        assertEquals(
            "東京恵み教会",
            localizedDomainText(
                Language.CHINESE_SIMPLIFIED,
                emptyList(),
                english = "Tokyo Grace Church",
                japanese = "東京恵み教会",
            ),
        )
    }

    @Test
    fun supportedLanguageCodesComeOnlyFromLanguageEnum() {
        assertEquals(listOf("ja", "en", "ko", "pt", "id", "zh-Hans", "zh-Hant"), supportedLanguageCodes)
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

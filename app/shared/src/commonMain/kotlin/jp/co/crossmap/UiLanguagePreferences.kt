package jp.co.crossmap

interface UiLanguagePreferences {
    fun readLanguageCode(): String?
    fun writeLanguageCode(languageCode: String)
}

object NoOpUiLanguagePreferences : UiLanguagePreferences {
    override fun readLanguageCode(): String? = null
    override fun writeLanguageCode(languageCode: String) = Unit
}

internal fun initialUiLanguage(savedLanguageCode: String?, osLocaleCode: String?): Language =
    Language.fromCode(savedLanguageCode) ?: Language.fromCodeOrEnglish(osLocaleCode)

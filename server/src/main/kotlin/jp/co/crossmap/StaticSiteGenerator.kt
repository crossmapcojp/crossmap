package jp.co.crossmap

import java.text.Normalizer

/**
 * Produces the stable, language-independent church slug used by every localized page.
 * HTML generation belongs to [LocalizedStaticSiteGenerator].
 */
class StaticSiteGenerator {
    internal fun pageSlug(denominationEnglishName: String?, churchEnglishName: String): String {
        val denominationSlug = denominationEnglishName?.takeIf(String::isNotBlank)?.toUrlSlug()
        val churchSlug = churchEnglishName.toUrlSlug()
        return if (denominationSlug != null &&
            (churchSlug == denominationSlug || churchSlug.startsWith("$denominationSlug-"))
        ) {
            churchSlug
        } else {
            listOfNotNull(denominationSlug, churchSlug).joinToString("-")
        }.also { require(it.isNotBlank()) { "English names did not produce a URL slug" } }
    }

    private fun String.toUrlSlug(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""['’]"""), "")
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')
}

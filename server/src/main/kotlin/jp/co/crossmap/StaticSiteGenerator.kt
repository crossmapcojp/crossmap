package jp.co.crossmap

import java.text.Normalizer

/**
 * Produces the stable, language-independent church slug used by every localized page.
 * HTML generation belongs to [LocalizedStaticSiteGenerator].
 */
class StaticSiteGenerator {
    internal fun pageSlug(denominationEnglishName: String?, churchEnglishName: String): String {
        val rawDenominationSlug = denominationEnglishName?.takeIf(String::isNotBlank)?.toUrlSlug()
        val rawChurchSlug = churchEnglishName.toUrlSlug()
        val catholic = rawDenominationSlug == CATHOLIC_DENOMINATION_SLUG ||
            rawChurchSlug.startsWith("$CATHOLIC_DENOMINATION_SLUG-")
        val denominationSlug = rawDenominationSlug?.takeUnless { catholic }
        val churchSlug = if (catholic) rawChurchSlug.toCatholicChurchSlug() else rawChurchSlug
        return if (denominationSlug != null &&
            (churchSlug == denominationSlug || churchSlug.startsWith("$denominationSlug-"))
        ) {
            churchSlug
        } else {
            listOfNotNull(denominationSlug, churchSlug).joinToString("-")
        }.also { require(it.isNotBlank()) { "English names did not produce a URL slug" } }
    }

    private fun String.toCatholicChurchSlug(): String {
        if (this == CATHOLIC_DENOMINATION_SLUG) return "catholic-church"
        if (startsWith("$CATHOLIC_DENOMINATION_SLUG-")) {
            val churchName = removePrefix("$CATHOLIC_DENOMINATION_SLUG-")
            return if (churchName.endsWith("-church")) {
                "${churchName.removeSuffix("-church")}-catholic-church"
            } else {
                "$churchName-catholic-church"
            }
        }
        Regex("^catholic-(.+)-church$").matchEntire(this)?.let { match ->
            return "${match.groupValues[1]}-catholic-church"
        }
        return replace(Regex("-catholic-church-in-japan$"), "-catholic-church")
    }

    private fun String.toUrlSlug(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""['’]"""), "")
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private companion object {
        const val CATHOLIC_DENOMINATION_SLUG = "catholic-church-in-japan"
    }
}

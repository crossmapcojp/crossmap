package jp.co.crossmap.crawl.denomination

import java.net.URI
import jp.co.crossmap.JapaneseAddressNormalizer
import jp.co.crossmap.SocialProfile
import jp.co.crossmap.crawl.SocialUrlNormalizer
import org.jsoup.nodes.Element

internal object DirectoryCrawlerSupport {
    private val addressPattern = Regex(
        "〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}\\s*[^|｜\\n]*?(?=\\s*(?:TEL|Tel|電話|FAX|Fax|主任牧師|担任牧師|副牧師|牧師|伝道師|宣教師|司祭|教職|$))",
    )
    private val phone = Regex("(?:TEL|Tel|電話)\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]{8,})")
    private val fax = Regex("(?:FAX|Fax)\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]{8,})")
    private val email = Regex("[A-Z0-9._%+\\-]+(?:@|＠|\\s*(?:\\[at]|\\(at\\)|※)\\s*)[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    fun normalizeAddress(value: String): String = toZenkakuAddressBody(JapaneseAddressNormalizer.normalize(
        value.replace(Regex("\\s*(?:TEL|Tel|電話|FAX|Fax).*$"), "").trim(),
    ).normalized)

    private fun toZenkakuAddressBody(value: String): String {
        val postalEnd = Regex("^〒?\\d{3}-\\d{4}").find(value)?.range?.last?.plus(1) ?: 0
        return buildString(value.length) {
            value.forEachIndexed { index, character ->
                append(
                    when {
                        index < postalEnd -> character
                        character in '0'..'9' -> (character.code + 0xFEE0).toChar()
                        character == '-' -> '−'
                        else -> character
                    },
                )
            }
        }
    }

    fun addressFromText(text: String): String = addressPattern.find(text)?.value?.trim()?.let(::normalizeAddress).orEmpty()

    fun phoneFromText(text: String): String = phone.find(text)?.groupValues?.get(1)?.trim().orEmpty()

    fun faxFromText(text: String): String = fax.find(text)?.groupValues?.get(1)?.trim().orEmpty()

    fun socialProfiles(links: Iterable<Element>): List<SocialProfile> = links.mapNotNull { link ->
        val href = link.absUrl("href")
        val platform = SocialUrlNormalizer.platform(href) ?: return@mapNotNull null
        SocialProfile(platform, SocialUrlNormalizer.canonical(href, platform), SocialUrlNormalizer.handle(href))
    }.distinctBy { it.platform to it.url }

    fun externalWebsite(links: Iterable<Element>, denominationHost: String): String = links.firstOrNull { link ->
        val href = link.absUrl("href")
        href.startsWith("http") && SocialUrlNormalizer.platform(href) == null &&
            runCatching { URI(href).host != denominationHost }.getOrDefault(false)
    }?.absUrl("href").orEmpty()

    fun extractEmail(text: String, hrefs: List<String> = emptyList()): String =
        (hrefs.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }?.substringAfter(':')
            ?: email.find(text)?.value.orEmpty())
            .replace(Regex("\\s*(?:\\[at]|\\(at\\)|※|＠)\\s*", RegexOption.IGNORE_CASE), "@")
            .trim()
}

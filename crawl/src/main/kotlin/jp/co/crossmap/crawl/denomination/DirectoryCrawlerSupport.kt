package jp.co.crossmap.crawl.denomination

import java.net.URI
import jp.co.crossmap.JapaneseAddressNormalizer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object DirectoryCrawlerSupport {
    private val postal = Regex("〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}")
    private val address = Regex(
        "〒?\\s*[0-9０-９]{3}[-ー－‐]?[0-9０-９]{4}\\s*[^|｜\\n]*?(?=\\s*(?:TEL|Tel|電話|FAX|Fax|主任牧師|担任牧師|副牧師|牧師|伝道師|宣教師|司祭|教職|$))",
    )
    private val phone = Regex("(?:TEL|Tel|電話)\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]{8,})")
    private val fax = Regex("(?:FAX|Fax)\\s*[:：]?\\s*([0-9０-９()（）+\\-ー－‐/\\s]{8,})")
    private val churchName = Regex("教会|伝道所|チャペル|センター|集会|キリストの群れ|聖堂")

    fun blocks(document: Document, selectors: String): List<Element> = document.select(selectors)
        .filter { postal.containsMatchIn(it.text()) }
        .filter { element -> element.select(selectors).none { it !== element && postal.containsMatchIn(it.text()) } }
        .distinctBy { it.text() }

    fun churchFromBlock(
        block: Element,
        denominationHost: String,
        jurisdiction: String = "",
    ): OfficialDenominationChurch? {
        val text = block.text().trim()
        val parsedAddress = address.find(text)?.value?.trim()?.let(::normalizeAddress).orEmpty()
        if (parsedAddress.isBlank()) return null
        val nameElement = block.select("h1,h2,h3,h4,h5,h6,strong,b,a,th,td").firstOrNull { candidate ->
            val value = candidate.ownText().ifBlank { candidate.text() }.trim()
            churchName.containsMatchIn(value) && !postal.containsMatchIn(value) && value.length <= 80
        } ?: return null
        val name = nameElement.ownText().ifBlank { nameElement.text() }.trim()
        val links = block.select("a[href]")
        val officialDetail = links.firstOrNull { link ->
            runCatching { URI(link.absUrl("href")).host == denominationHost }.getOrDefault(false)
        }?.absUrl("href").orEmpty()
        val website = links.firstOrNull { link ->
            val href = link.absUrl("href")
            href.startsWith("http") && runCatching { URI(href).host != denominationHost }.getOrDefault(false)
        }?.absUrl("href").orEmpty()
        return OfficialDenominationChurch(
            name = name,
            address = parsedAddress,
            jurisdiction = jurisdiction,
            phone = phone.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
            fax = fax.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
            websiteUrl = website,
            denominationChurchListDetailPage = officialDetail,
            ministers = ChurchMinisterParser.parse(text),
        )
    }

    fun normalizeAddress(value: String): String = JapaneseAddressNormalizer.normalize(
        value.replace(Regex("\\s*(?:TEL|Tel|電話|FAX|Fax).*$"), "").trim(),
    ).normalized
}

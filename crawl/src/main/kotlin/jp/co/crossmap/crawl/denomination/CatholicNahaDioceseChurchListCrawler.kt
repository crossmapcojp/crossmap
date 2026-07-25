package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class CatholicNahaDioceseChurchListCrawler(
    override val sourceUrls: List<String>,
) : CatholicDioceseChurchListCrawler {
    override val dioceseSlug = "naha"
    override val jurisdictionNames = setOf("那覇教区・本島北部", "那覇教区・本島中部", "那覇教区・本島南部", "那覇教区・宮古・八重山")

    override fun parse(html: String): List<OfficialDenominationChurch> = parsePage(sourceUrls.first(), html)

    override fun parsePage(url: String, html: String): List<OfficialDenominationChurch> {
        val document = Jsoup.parse(html, url)
        var area = "本島南部"
        return document.select(".elementor-widget-heading h1, .elementor-widget-heading h2").mapNotNull { heading ->
            val text = heading.text().replace("\u200B", "").trim()
            if (text in setOf("本島北部", "本島中部", "本島南部", "宮古・八重山")) {
                area = text
                return@mapNotNull null
            }
            val name = text.takeIf { it.startsWith("カトリック") && it.contains("教会") } ?: return@mapNotNull null
            val card = heading.parents().firstOrNull { parent ->
                parent.select(".elementor-widget-heading h2").any { it.text().trim() == name } &&
                    parent.selectFirst(".elementor-widget-text-editor") != null
            } ?: return@mapNotNull null
            val content = card.selectFirst(".elementor-widget-text-editor")!!
            val body = content.text()
            val phones = phonePattern.findAll(body).map { it.value }.toList()
            val minister = Regex("主任司祭[：:]\\s*([^。]+?)(?:神父|$)").find(body)?.groupValues?.get(1)
                ?.let { ChurchMinisterParser.fromRoleAndNames("司祭", it) }.orEmpty()
            OfficialDenominationChurch(
                name = name, address = DirectoryCrawlerSupport.addressFromText(body), jurisdiction = "那覇教区・$area",
                phone = phones.getOrNull(0).orEmpty(), fax = phones.getOrNull(1).orEmpty(),
                email = DirectoryCrawlerSupport.extractEmail(body, content.select("a[href]").map { it.attr("href") }),
                socialProfiles = DirectoryCrawlerSupport.socialProfiles(content.select("a[href]")), ministers = minister,
                denominationChurchListDetailPage = "$url#${heading.parent()?.attr("data-id").orEmpty()}",
            )
        }.distinctBy(OfficialDenominationChurch::name)
    }

    private companion object {
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}

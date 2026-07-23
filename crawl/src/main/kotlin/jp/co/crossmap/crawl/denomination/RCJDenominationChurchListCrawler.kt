package jp.co.crossmap.crawl.denomination

import org.jsoup.Jsoup

class RCJDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId: String = "RCJ"
    override val denominationName: String = "日本キリスト改革派教会"
    override val sourceUrl: String = "https://www.rcj.gr.jp/_church_list/result_keyword.php"
    override val outputFileName: String = "rcj-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> {
        val doc = Jsoup.parse(html, sourceUrl)
        val churches = mutableListOf<OfficialDenominationChurch>()
        doc.select("section").forEach { section ->
            val h3 = section.selectFirst("h3") ?: return@forEach
            val name = h3.text().trim()
            if (name.isBlank()) return@forEach
            val h4 = section.selectFirst("h4") ?: return@forEach
            val addressText = h4.text().trim()
            val address = if (addressText.startsWith('〒')) addressText else "〒$addressText"
            val sectionText = section.text()
            val roleFirst = roleBeforeName.findAll(sectionText).flatMap { match ->
                val names = match.groupValues[2].substringAfterLast("教会の")
                ChurchMinisterParser.fromRoleAndNames(match.groupValues[1], names).asSequence()
            }
            val nameFirst = nameBeforeRole.findAll(sectionText).flatMap { match ->
                val name = match.groupValues[1]
                if (name in setOf("代理", "主任", "担任", "副")) emptySequence()
                else ChurchMinisterParser.fromRoleAndNames(match.groupValues[2], name).asSequence()
            }
            val ministers = (roleFirst + nameFirst).distinctBy { it.roleId to it.name }.toList()
            churches += OfficialDenominationChurch(
                name = name,
                address = address,
                ministers = ministers,
            )
        }
        return churches
    }

    private companion object {
        val roleBeforeName = Regex("(代理牧師|主任牧師|牧師|伝道師)\\s*(?:は|：|:)?\\s*([^。]+)")
        val nameBeforeRole = Regex("(?:^|[\\s、。に])([一-龯々〆ヵヶァ-ヶー]{2,12}(?:\\s+[一-龯々〆ヵヶァ-ヶー]{1,12})?(?:[（(][^）)]{1,20}[）)])?)\\s*(代理牧師|主任牧師|牧師|伝道師)(?=が|は|です|でした|[、。\\s])")
    }
}

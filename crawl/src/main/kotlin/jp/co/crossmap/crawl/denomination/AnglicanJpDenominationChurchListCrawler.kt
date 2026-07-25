package jp.co.crossmap.crawl.denomination

import java.awt.geom.Rectangle2D
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripperByArea

class AnglicanJpDenominationChurchListCrawler : SinglePageDenominationChurchListCrawler {
    override val denominationId = "ANGLICAN_JP"
    override val denominationName = "日本聖公会"
    override val sourceUrl = "https://www.nskk.org/province/others/Churches_NSKK.pdf"
    override val outputFileName = "anglican_jp-churches.json"

    override fun parse(html: String): List<OfficialDenominationChurch> = parseExtractedText(html)

    override fun parseLoadedPage(page: LoadedDenominationChurchPage): List<OfficialDenominationChurch> {
        require(page.bytes.startsWithPdfHeader()) { "日本聖公会の全国教会一覧がPDFではありません: ${page.url}" }
        val text = Loader.loadPDF(page.bytes).use { document ->
            buildString {
                document.pages.forEach { pdfPage ->
                    val box = pdfPage.cropBox
                    val columnWidth = box.width / 3f
                    repeat(3) { column ->
                        val stripper = PDFTextStripperByArea().apply { sortByPosition = true }
                        stripper.addRegion(
                            "column",
                            Rectangle2D.Float(column * columnWidth, 0f, columnWidth + 4f, box.height),
                        )
                        stripper.extractRegions(pdfPage)
                        appendLine(stripper.getTextForRegion("column"))
                    }
                }
            }
        }
        return parseExtractedText(text)
    }

    internal fun parseExtractedText(text: String): List<OfficialDenominationChurch> {
        var jurisdiction = ""
        return text.lineSequence().mapNotNull { rawLine ->
            val line = rawLine.replace(Regex("\\s+"), " ").trim()
            jurisdictionHeading.find(line)?.let { heading ->
                jurisdiction = heading.groupValues[1]
                return@mapNotNull null
            }
            val prefecture = prefecturePattern.find(line) ?: return@mapNotNull null
            val name = line.substring(0, prefecture.range.first).trim()
            if (!churchNamePattern.containsMatchIn(name) || excludedEntityPattern.containsMatchIn(name)) {
                return@mapNotNull null
            }
            val contact = line.substring(prefecture.range.first).trim()
            val phoneMatch = phonePattern.find(contact)
            val address = contact.substring(0, phoneMatch?.range?.first ?: contact.length).trim()
            OfficialDenominationChurch(
                name = name,
                address = DirectoryCrawlerSupport.normalizeAddress(address),
                jurisdiction = jurisdiction,
                phone = phoneMatch?.value.orEmpty(),
            )
        }.distinctBy { it.name to it.address }.toList()
    }

    private fun ByteArray.startsWithPdfHeader(): Boolean = size >= 5 &&
        this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'D'.code.toByte() &&
        this[3] == 'F'.code.toByte() && this[4] == '-'.code.toByte()

    private companion object {
        val jurisdictionHeading = Regex("^＋([^/]+?教区)\\s*//")
        val prefecturePattern = Regex(
            "北海道|東京都|京都府|大阪府|神奈川県|和歌山県|鹿児島県|[一-龯]{2,3}県",
        )
        val churchNamePattern = Regex("教会|聖公会|礼拝堂|伝道所|ミッション|会衆|チャペル|祈りの家")
        val excludedEntityPattern = Regex("教区(?:事務所|教務所|会館)|学校|大学|学院|幼稚園|保育|病院|ホーム|センター|施設|修道院")
        val phonePattern = Regex("[0-9０-９]{2,5}[-‐－ー][0-9０-９]{1,4}[-‐－ー][0-9０-９]{3,4}")
    }
}

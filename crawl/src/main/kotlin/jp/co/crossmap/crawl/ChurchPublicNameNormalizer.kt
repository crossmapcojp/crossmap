package jp.co.crossmap.crawl

import java.text.Normalizer
import jp.co.crossmap.withoutInternalDenominationMarkers

/** Removes legal-entity labels that are metadata rather than part of a church's public name. */
internal object ChurchPublicNameNormalizer {
    private val enclosedReligiousCorporation = Regex(
        """[\(\[\{【〈《「『]\s*宗(?:教法人)?\s*[\)\]\}】〉》」』]""",
    )
    private val edgeSeparators = Regex("""^[\s/|｜・･:：;；,_，\-‐‑‒–—―~〜]+|[\s/|｜・･:：;；,_，\-‐‑‒–—―~〜]+$""")

    fun normalize(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(enclosedReligiousCorporation, "")
            .replace("宗教法人", "")
            .withoutInternalDenominationMarkers()
            .replace(Regex("""\s+"""), " ")
            .trim()
        return normalized.replace(edgeSeparators, "").trim()
    }
}

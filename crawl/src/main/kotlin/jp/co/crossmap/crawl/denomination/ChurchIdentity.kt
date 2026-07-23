package jp.co.crossmap.crawl.denomination

import java.net.URI
import java.text.Normalizer
import jp.co.crossmap.crawl.JapaneseEntityNormalizer

/** Comparable identity evidence shared by catalog and official-directory church records. */
data class ChurchIdentity(
    val name: String,
    val address: String,
    val websiteUrl: String,
) {
    fun matches(other: ChurchIdentity): Boolean = matchConfidence(other) != null

    fun matchConfidence(other: ChurchIdentity): Double? {
        val websiteMatches = comparableWebsite(websiteUrl)?.let { it == comparableWebsite(other.websiteUrl) } == true
        if (websiteMatches) return 1.0

        val leftPostalCode = postalCode(address)
        val rightPostalCode = postalCode(other.address)
        if (leftPostalCode != null && rightPostalCode != null && leftPostalCode != rightPostalCode) return null

        val leftName = JapaneseEntityNormalizer.name(name)
        val rightName = JapaneseEntityNormalizer.name(other.name)
        val exactName = leftName.isNotBlank() && leftName == rightName
        val leftStem = comparisonStem(leftName)
        val rightStem = comparisonStem(rightName)
        val stemScore = when {
            leftStem.isBlank() || rightStem.isBlank() -> 0.0
            leftStem == rightStem -> 1.0
            minOf(leftStem.length, rightStem.length) >= 2 &&
                (leftStem.contains(rightStem) || rightStem.contains(leftStem)) -> 0.95
            else -> JapaneseEntityNormalizer.deterministicNameScore(leftStem, rightStem).toDouble()
        }
        val nameScore = maxOf(
            JapaneseEntityNormalizer.deterministicNameScore(name, other.name).toDouble(),
            stemScore,
        )
        val addressScore = JapaneseEntityNormalizer.deterministicAddressScore(address, other.address).toDouble()
        val samePostalCode = leftPostalCode != null && leftPostalCode == rightPostalCode
        val sameMunicipality = municipality(address)?.let { it == municipality(other.address) } == true

        return when {
            exactName && other.address.isBlank() -> 0.90
            exactName && samePostalCode -> 0.99
            exactName && sameMunicipality -> 0.96
            exactName && addressScore >= 0.70 -> 0.72 + addressScore * 0.28
            samePostalCode && stemScore >= 0.70 -> 0.78 + stemScore * 0.20
            sameMunicipality && stemScore >= 0.92 -> 0.76 + stemScore * 0.20
            nameScore >= 0.82 && addressScore >= 0.82 -> nameScore * 0.55 + addressScore * 0.45
            addressScore >= 0.96 && nameScore >= 0.65 -> nameScore * 0.45 + addressScore * 0.55
            else -> null
        }
    }

    private fun comparableWebsite(value: String): String? {
        if (value.isBlank() || "google.com/maps" in value || "google.com/maps/contrib" in value) return null
        return runCatching {
            val uri = URI(value)
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
            "$host${uri.path.orEmpty().trimEnd('/')}"
        }.getOrNull()
    }

    private fun postalCode(value: String): String? = Regex("\\d{3}-?\\d{4}")
        .find(value)
        ?.value
        ?.replace("-", "")

    private fun comparisonStem(value: String): String =
        value.replace(Regex("(?:キリスト)?教会|伝道所|チャペル|礼拝堂"), "")

    private fun municipality(value: String): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("〒?\\d{3}-?\\d{4}"), "")
            .replace(Regex("^.*?[都道府県]"), "")
            .replace(Regex("\\s+"), "")
        return Regex("^.{1,16}?[市区町村]").find(normalized)?.value
    }
}

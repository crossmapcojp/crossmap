package jp.co.crossmap.crawl.denomination

import java.net.URI
import jp.co.crossmap.crawl.JapaneseEntityNormalizer
import jp.co.crossmap.crawl.EnglishJapaneseAddressMatcher

/** Comparable identity evidence shared by catalog and official-directory church records. */
data class ChurchIdentity(
    val name: String,
    val address: String,
    val websiteUrl: String,
    val alternateNames: List<String> = emptyList(),
) {
    private val normalizedWebsite = comparableWebsite(websiteUrl)
    private val normalizedPostalCode = postalCode(address)
    private val normalizedName = JapaneseEntityNormalizer.name(name)
    private val normalizedNames = (listOf(normalizedName) + alternateNames.map(JapaneseEntityNormalizer::name))
        .filter(String::isNotBlank)
        .distinct()
    private val normalizedStems = normalizedNames.map(::comparisonStem).filter(String::isNotBlank).distinct()
    private val normalizedAddress = JapaneseEntityNormalizer.address(address)

    fun matches(other: ChurchIdentity): Boolean = matchConfidence(other) != null

    fun matchConfidence(other: ChurchIdentity): Double? {
        val websiteMatches = normalizedWebsite?.let { it == other.normalizedWebsite } == true
        if (websiteMatches) return 1.0

        val leftPostalCode = normalizedPostalCode
        val rightPostalCode = other.normalizedPostalCode
        if (leftPostalCode != null && rightPostalCode != null && leftPostalCode != rightPostalCode) return null

        val exactName = normalizedNames.any { it in other.normalizedNames }
        val stemScore = normalizedStems.maxOfOrNull { leftStem ->
            other.normalizedStems.maxOfOrNull { rightStem ->
                when {
                    leftStem == rightStem -> 1.0
                    minOf(leftStem.length, rightStem.length) >= 2 &&
                        (leftStem.contains(rightStem) || rightStem.contains(leftStem)) -> 0.95
                    else -> JapaneseEntityNormalizer.deterministicNormalizedNameScore(leftStem, rightStem).toDouble()
                }
            } ?: 0.0
        } ?: 0.0
        val nameScore = maxOf(
            normalizedNames.maxOfOrNull { leftName ->
                other.normalizedNames.maxOfOrNull { rightName ->
                    JapaneseEntityNormalizer.deterministicNormalizedNameScore(leftName, rightName).toDouble()
                } ?: 0.0
            } ?: 0.0,
            stemScore,
        )
        val addressScore = maxOf(
            JapaneseEntityNormalizer.deterministicNormalizedAddressScore(
                normalizedAddress,
                other.normalizedAddress,
            ).toDouble(),
            EnglishJapaneseAddressMatcher.similarity(address, other.address),
            EnglishJapaneseAddressMatcher.similarity(other.address, address),
        )
        val samePostalCode = leftPostalCode != null && leftPostalCode == rightPostalCode

        return when {
            exactName && other.address.isBlank() -> 0.90
            exactName && samePostalCode -> 0.99
            exactName && addressScore >= 0.70 -> 0.72 + addressScore * 0.28
            samePostalCode && stemScore >= 0.70 && addressScore >= 0.70 -> 0.58 + stemScore * 0.20 + addressScore * 0.20
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

}

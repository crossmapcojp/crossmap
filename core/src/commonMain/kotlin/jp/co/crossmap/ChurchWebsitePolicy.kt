package jp.co.crossmap

/**
 * Prevents third-party church directories from being presented or crawled as a
 * congregation's own website.
 */
class ChurchWebsitePolicy(excludedDomains: Collection<String>) {
    val excludedDomains: Set<String> = excludedDomains
        .mapNotNull(::normalizeDomain)
        .toSet()

    fun isExcluded(url: String): Boolean {
        val host = urlHost(url) ?: return false
        return excludedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    fun isCrawlableChurchWebsite(url: String): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://")) || isExcluded(url) || isSocialPlatform(url)) return false
        val host = urlHost(url) ?: return false
        return !(host == "google.com" || host.endsWith(".google.com") ||
            host == "google.co.jp" || host.endsWith(".google.co.jp")) ||
            !url.substringAfter("://").substringAfter('/').startsWith("maps")
    }

    fun isSocialPlatform(url: String): Boolean {
        val host = urlHost(url) ?: return false
        return socialPlatformDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    fun publicWebsiteUrl(url: String?, googleCid: String?, churchId: String? = null): String {
        val candidate = url.orEmpty().trim()
        if (candidate.isNotBlank() && !isExcluded(candidate) && !isSocialPlatform(candidate)) return candidate
        val cid = googleCid?.takeIf(String::isNotBlank)
            ?: churchId?.takeIf { it.startsWith("google:") }?.removePrefix("google:")
        return cid?.let(::googleMapsPlaceUrl).orEmpty()
    }

    fun publicWebsiteUrl(church: ChurchRecord): String =
        publicWebsiteUrl(church.websiteUrl, church.googleCid, church.id)

    companion object {
        private val socialPlatformDomains = setOf(
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com",
            "youtube.com",
            "youtu.be",
        )

        fun parse(text: String): Set<String> = text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter(String::isNotBlank)
            .mapNotNull(::normalizeDomain)
            .toSet()

        fun googleMapsPlaceUrl(googleCid: String): String = "https://www.google.com/maps?cid=$googleCid"

        private fun normalizeDomain(value: String): String? {
            val host = urlHost(value) ?: value.trim().lowercase()
                .removePrefix("www.")
                .trim('.')
            return host.takeIf { it.isNotBlank() && '.' in it }
        }

        private fun urlHost(value: String): String? {
            val trimmed = value.trim()
            val authority = when {
                "://" in trimmed -> trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                '/' in trimmed -> trimmed.substringBefore('/')
                else -> trimmed
            }.substringAfterLast('@')
            val host = authority.substringBefore(':').lowercase().removePrefix("www.").trim('.')
            return host.takeIf { it.isNotBlank() && '.' in it }
        }
    }
}

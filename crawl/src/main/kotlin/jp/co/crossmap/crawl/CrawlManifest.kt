package jp.co.crossmap.crawl

import kotlinx.serialization.Serializable

@Serializable
data class CrawlManifestEntry(
    val churchId: String,
    val requestedUrl: String,
    val finalUrl: String,
    val cachePath: String,
    val fetchedAt: String,
    val status: Int,
    val contentHash: String,
    val error: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val acquisition: String = "HTTP",
)

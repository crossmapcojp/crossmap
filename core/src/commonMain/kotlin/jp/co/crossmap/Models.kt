package jp.co.crossmap

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

@Serializable
enum class CrawledContentType { WEBSITE_PAGE, SERMON }

@Serializable
data class SermonMetadata(
    val title: String? = null,
    val speaker: String? = null,
    val publishedAt: String? = null,
    val scriptureReferences: List<String> = emptyList(),
    val mediaUrl: String? = null,
)

@Serializable
data class CrawledPage(
    val url: String,
    val finalUrl: String = url,
    val title: String = "",
    val text: String = "",
    val fetchedAt: String = "",
    val contentHash: String = "",
    val status: Int = 200,
    val error: String? = null,
    val contentType: CrawledContentType = CrawledContentType.WEBSITE_PAGE,
    val sermon: SermonMetadata? = null,
)

@Serializable
enum class SocialPlatform { FACEBOOK, X, INSTAGRAM, YOUTUBE }

@Serializable
data class SocialProfile(
    val platform: SocialPlatform,
    val url: String,
    val handle: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val discoveredAt: String = "",
    val contentHash: String? = null,
)

@Serializable
enum class DeterminationSource(val tag: String) {
    PROGRAMMATIC("[programmatically-determined]"),
    LLM("[llm-determined]"),
    HUMAN("[human-determined]"),
}

@Serializable
data class FieldDetermination(
    val field: String,
    val value: String,
    val source: DeterminationSource,
    val confidence: Double,
    val evidence: List<String> = emptyList(),
    val model: String? = null,
    val determinedAt: String = "",
)

@Serializable
data class LocalizedName(
    val languageCode: String,
    val name: String,
)

@Serializable
data class ChurchRecord(
    val id: String,
    val googleCid: String? = null,
    val name: String,
    val englishName: String,
    val localizedNames: List<LocalizedName> = emptyList(),
    val localizedDenominationNames: List<LocalizedName> = emptyList(),
    val titleLanguages: List<String> = emptyList(),
    val denominationId: String? = null,
    val category: String? = null,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String,
    val pages: List<CrawledPage> = emptyList(),
    val socialProfiles: List<SocialProfile> = emptyList(),
    val determinations: List<FieldDetermination> = emptyList(),
    val updatedAt: String = "",
) {
    init {
        require(englishName.isNotBlank()) { "ChurchRecord.englishName must not be blank" }
    }
}

@Serializable
enum class GeoNameType { PREFECTURE, MUNICIPALITY, WARD, DEVICE }

@Serializable
data class GeoName(
    val code: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val type: GeoNameType,
    val prefectureCode: String,
    val center: GeoPoint,
    val coveringRadiusKm: Double,
    val translations: Map<String, String> = emptyMap(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val includeInPrefectureSearch: Boolean = true,
)

@Serializable
data class ChurchSearchRequest(
    val query: String,
    val offset: Int = 0,
    val limit: Int = 20,
    val radiusKm: Double? = null,
    val userLocation: GeoPoint? = null,
    val titleLanguages: List<String> = emptyList(),
)

@Serializable
data class ResolvedLocation(
    val matchedText: String,
    val code: String,
    val name: String,
    val type: GeoNameType,
    val center: GeoPoint,
    val radiusKm: Double,
)

@Serializable
data class MatchedPage(
    val url: String,
    val title: String = "",
    val snippet: String = "",
)

@Serializable
data class ChurchSearchHit(
    val churchId: String,
    val name: String,
    val englishName: String,
    val localizedNames: List<LocalizedName> = emptyList(),
    val localizedDenominationNames: List<LocalizedName> = emptyList(),
    val titleLanguages: List<String> = emptyList(),
    val denominationId: String? = null,
    val category: String? = null,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String,
    val score: Float,
    val distanceKm: Double? = null,
    val matchedPages: List<MatchedPage> = emptyList(),
    val socialProfiles: List<SocialProfile> = emptyList(),
    val detailUrl: String? = null,
)

@Serializable
data class ChurchSearchResponse(
    val schemaVersion: Int = 1,
    val indexVersion: String,
    val query: String,
    val textQuery: String,
    val resolvedLocations: List<ResolvedLocation> = emptyList(),
    val total: Long,
    val offset: Int,
    val limit: Int,
    val hits: List<ChurchSearchHit>,
)

@Serializable
data class ChurchDetailResponse(
    val schemaVersion: Int = 1,
    val indexVersion: String,
    val churchId: String,
    val name: String,
    val englishName: String,
    val localizedNames: List<LocalizedName> = emptyList(),
    val localizedDenominationNames: List<LocalizedName> = emptyList(),
    val titleLanguages: List<String> = emptyList(),
    val denominationId: String? = null,
    val category: String? = null,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String,
    val socialProfiles: List<SocialProfile> = emptyList(),
)

@Serializable
data class IndexManifest(
    val schemaVersion: Int = ChurchIndex.SCHEMA_VERSION,
    val indexVersion: String,
    val corpus: SearchCorpus = SearchCorpus.CHURCHES,
    val luceneVersion: String,
    val createdAt: String,
    val documentCount: Int,
    val languages: List<String> = listOf("ja"),
    val sourceSha256: String = "",
    val archiveFile: String? = null,
    val archiveSize: Long? = null,
    val sha256: String? = null,
)

@Serializable
data class ChurchPageManifest(
    val schemaVersion: Int = 1,
    val sourceSha256: String,
    val pages: Map<String, String>,
)

@Serializable
enum class SearchCorpus { CHURCHES, SERMONS }

@Serializable
data class ApiError(val code: String, val message: String)

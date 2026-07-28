package jp.co.crossmap.catalog

import jp.co.crossmap.Language

import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.SocialProfile
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class ChurchId(val value: String) {
    init { require(value.isNotBlank()) { "ChurchId must not be blank" } }
}

@Serializable
@JvmInline
value class DenominationId(val value: String) {
    init { require(value.isNotBlank()) { "DenominationId must not be blank" } }
}

@Serializable
@JvmInline
value class LocationId(val value: String) {
    init { require(value.isNotBlank()) { "LocationId must not be blank" } }
}

@Serializable
@JvmInline
value class WebsiteId(val value: String) {
    init { require(value.isNotBlank()) { "WebsiteId must not be blank" } }
}

@Serializable
@JvmInline
value class SocialAccountId(val value: String) {
    init { require(value.isNotBlank()) { "SocialAccountId must not be blank" } }
}

@Serializable
@JvmInline
value class PersonId(val value: String) {
    init { require(value.isNotBlank()) { "PersonId must not be blank" } }
}

@Serializable
data class EntityRef<T>(val id: T)

@Serializable
data class MultilingualText(val values: Map<String, String>) {
    init {
        require(values.keys.all(LANGUAGE_CODE::matches)) { "Invalid multilingual language code" }
        require(values.values.none(String::isBlank)) { "Multilingual values must not be blank" }
    }

    operator fun get(languageCode: String): String? = values[canonicalLanguageCode(languageCode)]

    companion object {
        private val LANGUAGE_CODE = Regex("^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")

        fun from(names: List<LocalizedName>): MultilingualText = MultilingualText(
            names.associate { canonicalLanguageCode(it.languageCode) to it.name.trim() }
                .filterValues(String::isNotBlank),
        )

        private fun canonicalLanguageCode(value: String): String =
            Language.fromCode(value)?.code ?: value.substringBefore('-').lowercase()
    }
}

@Serializable
data class Church(
    val id: ChurchId,
    val googlePlaceId: String?,
    val names: MultilingualText,
    val primaryName: String,
    val englishName: String,
    val titleLanguages: List<String>,
    val category: String?,
    val address: String,
    val location: GeoPoint,
    val email: String?,
    val updatedAt: String,
    val denomination: EntityRef<DenominationId>?,
)

@Serializable
data class Website(
    val id: WebsiteId,
    val url: String,
    val normalizedUrl: String,
)

@Serializable
data class SocialMediaAccount(
    val id: SocialAccountId,
    val profile: SocialProfile,
    val normalizedUrl: String,
)

@Serializable
data class ChurchSummary(
    val id: ChurchId,
    val name: String,
    val englishName: String,
    val denominationId: DenominationId?,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String?,
)

@Serializable
data class ChurchDetails(
    val record: ChurchRecord,
)

@Serializable
data class ChurchMapMarker(
    val id: ChurchId,
    val name: String,
    val location: GeoPoint,
    val denominationId: DenominationId?,
)

@Serializable
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south <= north) { "south must not exceed north" }
        require(west <= east) { "west must not exceed east" }
    }
}

@Serializable
data class PageRequest(val offset: Int = 0, val limit: Int = 50) {
    init {
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
    }
}

@Serializable
data class Page<T>(val items: List<T>, val offset: Int, val limit: Int, val total: Long)

interface ChurchRepository {
    suspend fun findById(id: ChurchId): ChurchDetails?
    suspend fun findSummaryById(id: ChurchId): ChurchSummary?
    suspend fun listPage(request: PageRequest): Page<ChurchSummary>
    suspend fun findMapMarkers(bounds: GeoBounds? = null): List<ChurchMapMarker>
    suspend fun count(): Long
    suspend fun upsertCore(church: Church)
    suspend fun setDenomination(churchId: ChurchId, denominationId: DenominationId?)
    suspend fun replaceWebsites(churchId: ChurchId, websites: List<Website>)
    suspend fun replaceSocialAccounts(churchId: ChurchId, accounts: List<SocialMediaAccount>)
    suspend fun replaceMinisters(churchId: ChurchId, ministers: List<ChurchMinister>)
}

fun ChurchRecord.toCatalogChurch(): Church {
    val canonicalNames = buildList {
        addAll(localizedNames)
        add(LocalizedName("ja", name))
        englishName.takeIf(String::isNotBlank)?.let { add(LocalizedName("en", it)) }
    }.distinctBy { it.languageCode.substringBefore('-').lowercase() }
    return Church(
        id = ChurchId(id),
        googlePlaceId = googleCid,
        names = MultilingualText.from(canonicalNames),
        primaryName = name,
        englishName = englishName,
        titleLanguages = titleLanguages.distinct(),
        category = category,
        address = address,
        location = location,
        email = email,
        updatedAt = updatedAt,
        denomination = denominationId?.takeIf(String::isNotBlank)?.let { EntityRef(DenominationId(it)) },
    )
}

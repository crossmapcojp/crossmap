package jp.co.crossmap.catalog.importer

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import jp.co.crossmap.CanonicalNameLanguage
import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.SocialPlatform
import jp.co.crossmap.SocialProfile
import jp.co.crossmap.toCanonicalLocalizedNames
import jp.co.crossmap.toCanonicalNameMap
import jp.co.crossmap.catalog.ChurchId
import jp.co.crossmap.catalog.DenominationId
import jp.co.crossmap.catalog.MultilingualText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SourceMetadata(
    val path: String,
    val checksum: String,
    val recordIndex: Int,
)

data class DenominationImportRef(
    val id: DenominationId,
    val localizedNames: List<LocalizedName> = emptyList(),
)

data class WebsiteImportRecord(
    val id: String,
    val url: String,
    val normalizedUrl: String,
    val pages: List<CrawledPage>,
)

data class SocialAccountImportRecord(
    val id: String,
    val platform: SocialPlatform,
    val url: String,
    val normalizedUrl: String,
    val handle: String?,
    val displayName: String?,
    val description: String?,
    val discoveredAt: String,
    val contentHash: String?,
)

data class ChurchImportRecord(
    val id: ChurchId,
    val googlePlaceId: String?,
    val names: MultilingualText,
    val localizedNames: List<LocalizedName>,
    val primaryName: String,
    val englishName: String,
    val titleLanguages: List<String>,
    val denomination: DenominationImportRef?,
    val category: String?,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val website: WebsiteImportRecord?,
    val email: String?,
    val socialAccounts: List<SocialAccountImportRecord>,
    val ministers: List<ChurchMinister>,
    val determinations: List<FieldDetermination>,
    val updatedAt: String,
    val source: SourceMetadata,
)

@Serializable
data class RejectedChurchImportRecord(
    val recordIndex: Int,
    val id: String?,
    val reason: String,
)

data class NormalizedCatalogImport(
    val sourcePath: String,
    val sourceChecksum: String,
    val records: List<ChurchImportRecord>,
    val rejectedRecords: List<RejectedChurchImportRecord>,
    val warnings: List<String>,
    val duplicateCollapses: Int,
)

class LegacyJsonChurchCatalogSource(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun read(path: Path): NormalizedCatalogImport {
        val bytes = Files.readAllBytes(path)
        val checksum = bytes.sha256()
        val churches = json.decodeFromString<List<jp.co.crossmap.ChurchRecord>>(bytes.toString(Charsets.UTF_8))
        val rejected = mutableListOf<RejectedChurchImportRecord>()
        val warnings = mutableListOf<String>()
        var duplicateCollapses = 0
        val normalized = churches.mapIndexedNotNull { index, church ->
            try {
                normalize(church, SourceMetadata(path.toString(), checksum, index), warnings).also { record ->
                    duplicateCollapses += church.localizedNames.size + 2 - record.names.values.size
                    duplicateCollapses += church.socialProfiles.size - record.socialAccounts.size
                }
            } catch (failure: IllegalArgumentException) {
                rejected += RejectedChurchImportRecord(index, church.id.takeIf(String::isNotBlank), failure.message ?: "Invalid record")
                null
            }
        }.sortedBy { it.id.value }
        val duplicateIds = normalized.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        if (duplicateIds.isNotEmpty()) {
            warnings += "Duplicate church IDs: ${duplicateIds.keys.joinToString { it.value }}"
        }
        return NormalizedCatalogImport(
            sourcePath = path.toString(),
            sourceChecksum = checksum,
            records = normalized.distinctBy { it.id },
            rejectedRecords = rejected,
            warnings = warnings,
            duplicateCollapses = duplicateCollapses + duplicateIds.values.sumOf { it - 1 },
        )
    }

    internal fun normalize(
        church: jp.co.crossmap.ChurchRecord,
        source: SourceMetadata,
        warnings: MutableList<String> = mutableListOf(),
    ): ChurchImportRecord {
        val id = ChurchId(church.id.trim())
        require(church.location.latitude in -90.0..90.0) { "Invalid latitude for ${id.value}" }
        require(church.location.longitude in -180.0..180.0) { "Invalid longitude for ${id.value}" }
        val names = buildList {
            addAll(church.localizedNames)
            add(LocalizedName("ja", church.name.trim()))
            church.englishName.trim().takeIf(String::isNotBlank)?.let { add(LocalizedName("en", it)) }
        }.filter { localized -> CanonicalNameLanguage.entries.any { it.languageTag == localized.languageCode } }
            .toCanonicalNameMap()
            .toCanonicalLocalizedNames()
        require(names.isNotEmpty()) { "Missing name for ${id.value}" }
        val website = church.websiteUrl.trim().takeIf(String::isNotBlank)?.let { url ->
            val normalizedUrl = normalizeUrl(url, retainFragment = true)
            WebsiteImportRecord(
                id = stableId("website", normalizedUrl),
                url = url,
                normalizedUrl = normalizedUrl,
                pages = church.pages.sortedWith(compareBy(CrawledPage::url, CrawledPage::fetchedAt)),
            )
        }
        val socialAccounts = church.socialProfiles.mapNotNull { profile ->
            runCatching { normalizeSocialAccount(profile) }.getOrElse { failure ->
                warnings += "Church ${id.value} skipped invalid ${profile.platform.name} URL '${profile.url}': ${failure.message}"
                null
            }
        }
            .distinctBy { it.platform to it.normalizedUrl }
            .sortedWith(compareBy({ it.platform.name }, SocialAccountImportRecord::normalizedUrl))
        return ChurchImportRecord(
            id = id,
            googlePlaceId = church.googleCid?.trim()?.takeIf(String::isNotBlank),
            names = MultilingualText(names.associate { it.languageCode to it.name }),
            localizedNames = names,
            primaryName = church.name.trim(),
            englishName = church.englishName.trim(),
            titleLanguages = church.titleLanguages.map(String::trim).filter(String::isNotBlank).distinct().sorted(),
            denomination = church.denominationId?.trim()?.takeIf(String::isNotBlank)?.let {
                DenominationImportRef(
                    DenominationId(it),
                    canonicalNames(church.localizedDenominationNames),
                )
            },
            category = church.category?.trim()?.takeIf(String::isNotBlank),
            address = church.address.trim(),
            latitude = church.location.latitude,
            longitude = church.location.longitude,
            website = website,
            email = church.email?.trim()?.takeIf(String::isNotBlank),
            socialAccounts = socialAccounts,
            ministers = church.ministers.map { minister ->
                minister.copy(
                    name = minister.name.trim(),
                    localizedNames = canonicalNames(minister.localizedNames),
                    roleId = minister.roleId.trim(),
                    roleName = minister.roleName.trim(),
                    localizedRoleNames = canonicalNames(minister.localizedRoleNames),
                )
            }.sortedWith(compareBy(ChurchMinister::name, ChurchMinister::roleId)),
            determinations = church.determinations.sortedWith(compareBy(FieldDetermination::field, FieldDetermination::source)),
            updatedAt = church.updatedAt.trim(),
            source = source,
        )
    }

    private fun canonicalNames(names: List<LocalizedName>): List<LocalizedName> = names
        .filter { localized -> CanonicalNameLanguage.entries.any { it.languageTag == localized.languageCode } }
        .toCanonicalNameMap()
        .toCanonicalLocalizedNames()

    private fun normalizeSocialAccount(profile: SocialProfile): SocialAccountImportRecord? {
        val url = profile.url.trim().takeIf(String::isNotBlank) ?: return null
        val normalizedUrl = normalizeUrl(url)
        return SocialAccountImportRecord(
            id = stableId("social-${profile.platform.name.lowercase()}", normalizedUrl),
            platform = profile.platform,
            url = url,
            normalizedUrl = normalizedUrl,
            handle = profile.handle?.trim()?.takeIf(String::isNotBlank),
            displayName = profile.displayName?.trim()?.takeIf(String::isNotBlank),
            description = profile.description?.trim()?.takeIf(String::isNotBlank),
            discoveredAt = profile.discoveredAt.trim(),
            contentHash = profile.contentHash?.trim()?.takeIf(String::isNotBlank),
        )
    }
}

internal fun normalizeUrl(value: String, retainFragment: Boolean = false): String {
    val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("Invalid URL: $value") }
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Unsupported URL scheme: $value" }
    val host = uri.host?.lowercase() ?: throw IllegalArgumentException("URL has no host: $value")
    val path = uri.rawPath.orEmpty().let { if (it.length > 1) it.trimEnd('/') else it }
    return URI(
        uri.scheme.lowercase(), uri.userInfo, host, uri.port, path, uri.rawQuery,
        uri.rawFragment.takeIf { retainFragment },
    ).toASCIIString()
}

private fun stableId(namespace: String, value: String): String =
    "$namespace:${value.toByteArray().sha256().take(24)}"

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

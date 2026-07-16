package jp.co.crossmap.crawl

import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.CrawledPage
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.SocialProfile
import kotlinx.serialization.Serializable

/** Crawl-local record that may exist before mandatory publication fields are resolved. */
@Serializable
data class ChurchRecordDraft(
    val id: String,
    val googleCid: String? = null,
    val name: String,
    val englishName: String? = null,
    val localizedNames: List<LocalizedName> = emptyList(),
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
    fun toEnglishNameInput() = ChurchEnglishNameInput(
        id = id,
        name = name,
        existingEnglishName = englishName.takeIf {
            determinations.lastOrNull { determination -> determination.field == "englishName" }?.source ==
                DeterminationSource.HUMAN
        },
        denominationId = denominationId,
        address = address,
        location = location,
        websiteUrl = websiteUrl,
        pages = pages,
        socialProfiles = socialProfiles,
    )

    fun toChurchRecord(resolution: ResolvedChurchEnglishName, determinedAt: String): ChurchRecord = ChurchRecord(
        id = id,
        googleCid = googleCid,
        name = name,
        englishName = resolution.englishName,
        localizedNames = localizedNames,
        titleLanguages = titleLanguages,
        denominationId = denominationId,
        category = category,
        address = address,
        location = location,
        websiteUrl = websiteUrl,
        pages = pages,
        socialProfiles = socialProfiles,
        determinations = determinations.filterNot { it.field == "englishName" } + FieldDetermination(
            field = "englishName",
            value = resolution.englishName,
            source = resolution.source,
            confidence = resolution.confidence.toDouble(),
            evidence = resolution.evidence,
            model = resolution.model,
            determinedAt = determinedAt,
        ),
        updatedAt = updatedAt,
    )
}

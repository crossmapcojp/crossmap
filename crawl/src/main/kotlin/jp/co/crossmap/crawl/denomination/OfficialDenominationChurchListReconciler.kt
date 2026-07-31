package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.crawl.JapaneseEntityNormalizer
import jp.co.crossmap.crawl.NOT_DETERMINED
import jp.co.crossmap.crawl.CatholicChurchNameNormalizer
import kotlinx.serialization.json.Json

data class OfficialDenominationReconciliationReport(
    val churches: Int,
    val officialEntries: Int,
    val matchedOfficialEntries: Int,
    val assigned: Int,
    val removedUnsupportedLabels: Int,
    val unchanged: Int,
    val humanOverridesPreserved: Int,
    val unmatchedOfficialEntries: Int,
    val assignedEntries: List<OfficialDenominationReconciliationAuditEntry> = emptyList(),
    val removedUnsupportedLabelEntries: List<OfficialDenominationReconciliationAuditEntry> = emptyList(),
    val unmatchedOfficialEntryDetails: List<OfficialDenominationReconciliationAuditEntry> = emptyList(),
    val updatedChurches: List<ChurchRecord> = emptyList(),
) {
    fun toHumanReadableAuditLog(): String = buildString {
        appendAuditGroup("denominations_assigned", assignedEntries)
        appendLine()
        appendAuditGroup("unsupported_labels_removed", removedUnsupportedLabelEntries)
        appendLine()
        appendAuditGroup("unmatched_official_entries", unmatchedOfficialEntryDetails)
    }.trimEnd()
}

private fun OfficialDenominationChurch.officialEnglishName(): String =
    localizedNames.firstOrNull { it.languageCode == "en" }?.name.orEmpty()

private fun List<LocalizedName>.withOfficialNames(official: List<LocalizedName>): List<LocalizedName> {
    if (official.isEmpty()) return this
    val officialLanguages = official.mapTo(hashSetOf(), LocalizedName::languageCode)
    return (filterNot { it.languageCode in officialLanguages } + official)
        .distinctBy { it.languageCode to it.name }
}

data class OfficialDenominationReconciliationAuditEntry(
    val googleMapsSavedPlace: GoogleMapsSavedPlaceAuditData?,
    val denominationCrawler: DenominationCrawlerChurchAuditData,
)

data class GoogleMapsSavedPlaceAuditData(
    val churchId: String,
    val googlePlaceTitle: String,
    val namesByLanguage: Map<String, List<String>>,
    val address: String,
    val website: String,
    val detectedDenomination: String,
)

data class DenominationCrawlerChurchAuditData(
    val denominationName: String,
    val churchName: String?,
    val address: String?,
    val website: String?,
)

private fun StringBuilder.appendAuditGroup(
    name: String,
    entries: List<OfficialDenominationReconciliationAuditEntry>,
) {
    appendLine("$name (${entries.size})")
    entries.forEach { entry ->
        appendLine("church {")
        appendLine("  performed opperation: $name")
        entry.googleMapsSavedPlace?.let { google ->
            appendLine("  data from google map saved place {")
            appendLine("    church id: ${google.churchId.auditValue()}")
            appendLine("    google place title: ${google.googlePlaceTitle.auditValue("(not available in saved-place cache)")}")
            appendLine("    church names in all languages:")
            google.namesByLanguage.forEach { (language, names) ->
                appendLine("      ${language.auditValue()}: ${names.joinToString(" | ").auditValue()}")
            }
            appendLine("    church address: ${google.address.auditValue()}")
            appendLine("    church website: ${google.website.auditValue()}")
            appendLine("    detected denomination: ${google.detectedDenomination.auditValue()}")
            appendLine("  }")
        } ?: appendLine("  data from google map saved place: no matching church")

        val official = entry.denominationCrawler
        if (official.churchName == null) {
            appendLine("  data from denomination crawler: no matching official church")
            appendLine("    denomination name: ${official.denominationName.auditValue()}")
        } else {
            appendLine("  data from denomination crawler {")
            appendLine("    denomination name: ${official.denominationName.auditValue()}")
            appendLine("    church name: ${official.churchName.auditValue()}")
            appendLine("    church address: ${official.address.auditValue()}")
            appendLine("    church website: ${official.website.auditValue()}")
            appendLine("  }")
        }
        appendLine("}")
    }
}

private fun String?.auditValue(emptyValue: String = "(none)"): String = this
    ?.replace('\n', ' ')
    ?.replace('\r', ' ')
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: emptyValue

/**
 * Makes a fresh official list authoritative for denomination membership while preserving human review.
 * A name alone is not enough when an address is available: this prevents same-name churches belonging to
 * different organizations from being merged.
 */
class OfficialDenominationChurchListReconciler(
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
    private val now: () -> String = { Instant.now().toString() },
) {
    fun reconcile(
        churches: List<ChurchRecord>,
        lists: List<OfficialDenominationChurchList>,
        googlePlaceTitlesByChurchId: Map<String, String> = emptyMap(),
    ): OfficialDenominationReconciliationReport {
        val authoritative = lists.associateBy(OfficialDenominationChurchList::denominationId)
        val eligible = lists.flatMap { list ->
            list.churches.filter(OfficialDenominationChurch::eligibleForDenominationEvidence).mapIndexed { index, church ->
                OfficialEntry("${list.denominationId}:$index", list, church)
            }
        }
        val entriesByName = eligible.groupBy { entry ->
            entry.list.denominationId to comparableNameKey(entry.church.name, entry.list.denominationId)
        }
        val entriesByPostalCode = eligible.mapNotNull { entry -> postalCode(entry.church.address)?.let { it to entry } }
            .groupBy({ it.first }, { it.second })
        val entriesByAddressTail = eligible.mapNotNull { entry -> addressTail(entry.church.address)?.let { it to entry } }
            .groupBy({ it.first }, { it.second })
        val entriesByNameSignal = eligible.flatMap { entry ->
            nameSignals(entry.church.name, entry.list.denominationId).map { signal -> signal to entry }
        }.groupBy({ it.first }, { it.second })
        val officialIdentities = eligible.associate { entry -> entry.key to entry.toIdentity() }
        val churchIdentities = Array(churches.size) { mutableMapOf<String, ChurchIdentity>() }
        val matchedKeys = linkedSetOf<String>()
        var assigned = 0
        var removed = 0
        var unchanged = 0
        var humanPreserved = 0
        val assignedEntries = mutableListOf<OfficialDenominationReconciliationAuditEntry>()
        val removedEntries = mutableListOf<OfficialDenominationReconciliationAuditEntry>()
        val timestamp = now()

        val matchesByChurch = churches.mapIndexed { churchIndex, church ->
            val candidates = linkedSetOf<OfficialEntry>().apply {
                authoritative.keys.forEach { denominationId ->
                    addAll(entriesByName[denominationId to comparableNameKey(church.name, denominationId)].orEmpty())
                    nameSignals(church.name, denominationId).forEach { addAll(entriesByNameSignal[it].orEmpty()) }
                }
                postalCode(church.address)?.let { addAll(entriesByPostalCode[it].orEmpty()) }
                addressTail(church.address)?.let { addAll(entriesByAddressTail[it].orEmpty()) }
            }
            candidates.mapNotNull { entry ->
                val churchIdentity = churchIdentities[churchIndex].getOrPut(entry.list.denominationId) {
                    church.toIdentity(entry.list.denominationId)
                }
                churchIdentity.matchConfidence(officialIdentities.getValue(entry.key))?.let { entry to it }
            }
                .sortedByDescending(Pair<OfficialEntry, Double>::second)
        }
        val assignedByChurch = mutableMapOf<Int, Pair<OfficialEntry, Double>>()
        val usedOfficialEntries = linkedSetOf<String>()

        churches.forEachIndexed { index, church ->
            val human = church.determinations.lastOrNull { it.field == "denominationId" && it.source == DeterminationSource.HUMAN }
                ?: return@forEachIndexed
            matchesByChurch[index].firstOrNull { it.first.list.denominationId == human.value }?.let { match ->
                assignedByChurch[index] = match
                usedOfficialEntries += match.first.key
                matchedKeys += match.first.key
            }
        }
        matchesByChurch.flatMapIndexed { churchIndex, matches ->
            if (churches[churchIndex].determinations.any { it.field == "denominationId" && it.source == DeterminationSource.HUMAN }) {
                emptyList()
            } else {
                matches.map { (entry, score) -> CatalogOfficialMatch(churchIndex, entry, score) }
            }
        }.sortedWith(
            compareByDescending<CatalogOfficialMatch> { it.score }
                .thenByDescending { churches[it.churchIndex].denominationId == it.entry.list.denominationId },
        ).forEach { match ->
            if (match.churchIndex !in assignedByChurch && match.entry.key !in usedOfficialEntries) {
                assignedByChurch[match.churchIndex] = match.entry to match.score
                usedOfficialEntries += match.entry.key
                matchedKeys += match.entry.key
            }
        }

        val updated = churches.mapIndexed { index, church ->
            val current = church.denominationId
            val human = church.determinations.lastOrNull { it.field == "denominationId" && it.source == DeterminationSource.HUMAN }
            val officialAssignment = assignedByChurch[index]

            if (human != null) {
                if (current in authoritative && officialAssignment == null) humanPreserved++
                unchanged++
                return@mapIndexed officialAssignment?.first
                    ?.takeIf { it.list.denominationId == current }
                    ?.let { church.withOfficialDetails(it.church, timestamp) }
                    ?: church
            }

            if (current in authoritative) {
                if (officialAssignment?.first?.list?.denominationId == current) {
                    val assignment = requireNotNull(officialAssignment)
                    unchanged++
                    return@mapIndexed church.withOfficialDenomination(assignment.first, assignment.second, timestamp)
                }
                if (officialAssignment != null) {
                    assigned++
                    assignedEntries += auditEntry(church, officialAssignment.first, googlePlaceTitlesByChurchId)
                    return@mapIndexed church.withOfficialDenomination(officialAssignment.first, officialAssignment.second, timestamp)
                }
                removed++
                val authoritativeList = authoritative.getValue(current!!)
                removedEntries += auditEntry(church, authoritativeList, googlePlaceTitlesByChurchId)
                return@mapIndexed church.withUnsupportedDenominationRemoved(authoritativeList, timestamp)
            }

            if (officialAssignment != null) {
                assigned++
                assignedEntries += auditEntry(church, officialAssignment.first, googlePlaceTitlesByChurchId)
                return@mapIndexed church.withOfficialDenomination(officialAssignment.first, officialAssignment.second, timestamp)
            }
            unchanged++
            church
        }

        val normalizedChurches = updated.map(CatholicChurchNameNormalizer::normalize)
        return OfficialDenominationReconciliationReport(
            churches = churches.size,
            officialEntries = eligible.size,
            matchedOfficialEntries = matchedKeys.size,
            assigned = assigned,
            removedUnsupportedLabels = removed,
            unchanged = unchanged,
            humanOverridesPreserved = humanPreserved,
            unmatchedOfficialEntries = eligible.size - matchedKeys.size,
            assignedEntries = assignedEntries,
            removedUnsupportedLabelEntries = removedEntries,
            unmatchedOfficialEntryDetails = eligible.filterNot { it.key in matchedKeys }.map { entry ->
                OfficialDenominationReconciliationAuditEntry(
                    googleMapsSavedPlace = null,
                    denominationCrawler = entry.toAuditData(),
                )
            },
            updatedChurches = normalizedChurches,
        )
    }

    private fun auditEntry(
        church: ChurchRecord,
        entry: OfficialEntry,
        googlePlaceTitlesByChurchId: Map<String, String>,
    ) = OfficialDenominationReconciliationAuditEntry(
        googleMapsSavedPlace = church.toAuditData(googlePlaceTitlesByChurchId),
        denominationCrawler = entry.toAuditData(),
    )

    private fun auditEntry(
        church: ChurchRecord,
        list: OfficialDenominationChurchList,
        googlePlaceTitlesByChurchId: Map<String, String>,
    ) = OfficialDenominationReconciliationAuditEntry(
        googleMapsSavedPlace = church.toAuditData(googlePlaceTitlesByChurchId),
        denominationCrawler = DenominationCrawlerChurchAuditData(
            denominationName = list.denominationName,
            churchName = null,
            address = null,
            website = null,
        ),
    )

    private fun ChurchRecord.toAuditData(googlePlaceTitlesByChurchId: Map<String, String>): GoogleMapsSavedPlaceAuditData {
        val names = linkedMapOf<String, MutableList<String>>()
        fun addName(language: String, value: String) {
            value.takeIf(String::isNotBlank)?.let { names.getOrPut(language) { mutableListOf() } += it }
        }
        addName("ja", name)
        addName("en", englishName)
        localizedNames.forEach { addName(it.languageCode, it.name) }
        return GoogleMapsSavedPlaceAuditData(
            churchId = id,
            googlePlaceTitle = googlePlaceTitlesByChurchId[id].orEmpty(),
            namesByLanguage = names.mapValues { (_, values) -> values.distinct() },
            address = address,
            website = websiteUrl,
            detectedDenomination = denominationId ?: NOT_DETERMINED,
        )
    }

    private fun OfficialEntry.toAuditData() = DenominationCrawlerChurchAuditData(
        denominationName = list.denominationName,
        churchName = church.name,
        address = church.address,
        website = church.websiteUrl,
    )

    private fun ChurchRecord.toIdentity(denominationId: String) = ChurchIdentity(
        name = comparableName(name, denominationId),
        address = address,
        websiteUrl = websiteUrl,
        alternateNames = (listOf(englishName) + localizedNames.map { it.name })
            .map { comparableName(it, denominationId) },
    )

    private fun OfficialEntry.toIdentity() = ChurchIdentity(
        name = comparableName(church.name, list.denominationId),
        address = church.address,
        websiteUrl = church.websiteUrl,
        alternateNames = (listOf(church.officialEnglishName()) + church.localizedNames.map { it.name })
            .map { comparableName(it, list.denominationId) },
    )

    private fun comparableName(value: String, denominationId: String): String {
        var result = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("^[（(]?宗教法人[）)]?\\s*"), "")
            .replace(Regex("^宗[）)]\\s*"), "")
            .trim()
        result = when (denominationId) {
            "UCCJ" -> result.replace(Regex("日本(?:基督|キリスト|基督[（(]キリスト[）)])教団"), "")
            "JBC" -> result.replace("日本バプテスト連盟", "")
            "JHC" -> result.replace("日本ホーリネス教団", "")
            "RCJ" -> result.replace("日本キリスト改革派教会", "")
            "IGM" -> result.replace("イムマヌエル綜合伝道団", "")
            else -> result
        }
        return result.replace("基督", "キリスト").replace('ヶ', 'ケ').trim(' ', '・', '･')
    }

    private fun comparableNameKey(value: String, denominationId: String): String =
        JapaneseEntityNormalizer.name(comparableName(value, denominationId))

    private fun nameSignals(value: String, denominationId: String): Set<String> {
        val name = comparisonStem(value, denominationId)
        val size = if (name.length >= 5) 3 else 2
        return if (name.length <= size) setOf(name).filterTo(linkedSetOf()) { it.isNotBlank() }
        else (0..name.length - size).mapTo(linkedSetOf()) { name.substring(it, it + size) }
    }

    private fun comparisonStem(value: String, denominationId: String): String =
        comparableNameKey(value, denominationId)
            .replace(Regex("(?:キリスト)?教会|伝道所|チャペル|礼拝堂"), "")

    private fun postalCode(value: String): String? = Regex("\\d{3}-?\\d{4}").find(
        Normalizer.normalize(value, Normalizer.Form.NFKC),
    )?.value?.replace("-", "")

    private fun addressTail(value: String): String? = JapaneseEntityNormalizer.address(value)
        .takeIf { it.length >= 8 }
        ?.takeLast(8)

    private fun municipality(value: String): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("〒?\\d{3}-?\\d{4}"), "")
            .replace(Regex("^.*?[都道府県]"), "")
            .replace(Regex("\\s+"), "")
        return Regex("^.{1,16}?[市区町村]").find(normalized)?.value
    }

    private fun ChurchRecord.withOfficialDenomination(
        entry: OfficialEntry,
        confidence: Double,
        timestamp: String,
    ): ChurchRecord {
        val denominationId = entry.list.denominationId
        val determination = FieldDetermination(
            field = "denominationId",
            value = denominationId,
            source = DeterminationSource.PROGRAMMATIC,
            confidence = confidence,
            evidence = listOf(entry.list.sourceUrl, "Fresh official denomination church list: ${entry.church.name}"),
            determinedAt = timestamp,
        )
        return copy(
            denominationId = denominationId,
            englishName = entry.church.officialEnglishName().ifBlank { englishName },
            localizedNames = localizedNames.withOfficialNames(entry.church.localizedNames),
            ministers = entry.church.ministers,
            websiteUrl = websiteUrl.ifBlank { entry.church.websiteUrl },
            email = email ?: entry.church.email.ifBlank { null },
            socialProfiles = (socialProfiles + entry.church.socialProfiles).distinctBy { it.platform to it.url },
            determinations = determinations.filterNot { it.field == "denominationId" } + determination,
            updatedAt = timestamp,
        )
    }

    private fun ChurchRecord.withOfficialDetails(
        officialChurch: OfficialDenominationChurch,
        timestamp: String,
    ): ChurchRecord = if (officialChurch.ministers.isEmpty() && officialChurch.localizedNames.isEmpty()) {
        this
    } else {
        copy(
            englishName = officialChurch.officialEnglishName().ifBlank { englishName },
            localizedNames = localizedNames.withOfficialNames(officialChurch.localizedNames),
            ministers = officialChurch.ministers.ifEmpty { ministers },
            updatedAt = timestamp,
        )
    }

    private fun ChurchRecord.withUnsupportedDenominationRemoved(
        list: OfficialDenominationChurchList,
        timestamp: String,
    ): ChurchRecord {
        val determination = FieldDetermination(
            field = "denominationId",
            value = NOT_DETERMINED,
            source = DeterminationSource.PROGRAMMATIC,
            confidence = 1.0,
            evidence = listOf(list.sourceUrl, "Church is absent from the fresh official denomination church list"),
            determinedAt = timestamp,
        )
        return copy(
            denominationId = NOT_DETERMINED,
            determinations = determinations.filterNot { it.field == "denominationId" } + determination,
            updatedAt = timestamp,
        )
    }

    private fun atomicWrite(path: Path, content: String) {
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }

    private class OfficialEntry(
        val key: String,
        val list: OfficialDenominationChurchList,
        val church: OfficialDenominationChurch,
    ) {
        override fun equals(other: Any?): Boolean = other is OfficialEntry && key == other.key

        override fun hashCode(): Int = key.hashCode()
    }

    private data class CatalogOfficialMatch(
        val churchIndex: Int,
        val entry: OfficialEntry,
        val score: Double,
    )
}

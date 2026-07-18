package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.DeterminationSource
import jp.co.crossmap.FieldDetermination
import jp.co.crossmap.crawl.JapaneseEntityNormalizer
import jp.co.crossmap.crawl.NOT_DETERMINED
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
)

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
        catalogFile: Path,
        lists: List<OfficialDenominationChurchList>,
    ): OfficialDenominationReconciliationReport {
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalogFile))
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
        val matchedKeys = linkedSetOf<String>()
        var assigned = 0
        var removed = 0
        var unchanged = 0
        var humanPreserved = 0
        val timestamp = now()

        val matchesByChurch = churches.map { church ->
            val candidates = linkedSetOf<OfficialEntry>().apply {
                authoritative.keys.forEach { denominationId ->
                    addAll(entriesByName[denominationId to comparableNameKey(church.name, denominationId)].orEmpty())
                    nameSignals(church.name, denominationId).forEach { addAll(entriesByNameSignal[it].orEmpty()) }
                }
                postalCode(church.address)?.let { addAll(entriesByPostalCode[it].orEmpty()) }
                addressTail(church.address)?.let { addAll(entriesByAddressTail[it].orEmpty()) }
            }
            candidates.mapNotNull { entry -> match(church, entry)?.let { entry to it } }
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
                return@mapIndexed church
            }

            if (current in authoritative) {
                if (officialAssignment?.first?.list?.denominationId == current) {
                    val assignment = requireNotNull(officialAssignment)
                    unchanged++
                    return@mapIndexed church.withOfficialDenomination(assignment.first, assignment.second, timestamp)
                }
                if (officialAssignment != null) {
                    assigned++
                    return@mapIndexed church.withOfficialDenomination(officialAssignment.first, officialAssignment.second, timestamp)
                }
                removed++
                return@mapIndexed church.withUnsupportedDenominationRemoved(authoritative.getValue(current!!), timestamp)
            }

            if (officialAssignment != null) {
                assigned++
                return@mapIndexed church.withOfficialDenomination(officialAssignment.first, officialAssignment.second, timestamp)
            }
            unchanged++
            church
        }

        atomicWrite(catalogFile, json.encodeToString(updated))
        return OfficialDenominationReconciliationReport(
            churches = churches.size,
            officialEntries = eligible.size,
            matchedOfficialEntries = matchedKeys.size,
            assigned = assigned,
            removedUnsupportedLabels = removed,
            unchanged = unchanged,
            humanOverridesPreserved = humanPreserved,
            unmatchedOfficialEntries = eligible.size - matchedKeys.size,
        )
    }

    private fun match(church: ChurchRecord, entry: OfficialEntry): Double? {
        val churchName = comparableName(church.name, entry.list.denominationId)
        val officialName = comparableName(entry.church.name, entry.list.denominationId)
        val churchStem = comparisonStem(church.name, entry.list.denominationId)
        val officialStem = comparisonStem(entry.church.name, entry.list.denominationId)
        val stemScore = when {
            churchStem.isBlank() || officialStem.isBlank() -> 0.0
            churchStem == officialStem -> 1.0
            minOf(churchStem.length, officialStem.length) >= 2 &&
                (churchStem.contains(officialStem) || officialStem.contains(churchStem)) -> 0.95
            else -> JapaneseEntityNormalizer.deterministicNameScore(churchStem, officialStem).toDouble()
        }
        val nameScore = maxOf(
            JapaneseEntityNormalizer.deterministicNameScore(churchName, officialName).toDouble(),
            stemScore,
        )
        val exactName = JapaneseEntityNormalizer.name(churchName) == JapaneseEntityNormalizer.name(officialName)
        val addressScore = JapaneseEntityNormalizer.deterministicAddressScore(church.address, entry.church.address).toDouble()
        val samePostalCode = postalCode(church.address)?.let { it == postalCode(entry.church.address) } == true
        val sameMunicipality = municipality(church.address)?.let { it == municipality(entry.church.address) } == true
        return when {
            exactName && entry.church.address.isBlank() -> 0.90
            exactName && samePostalCode -> 0.99
            exactName && sameMunicipality -> 0.96
            exactName && addressScore >= 0.70 -> 0.72 + addressScore * 0.28
            samePostalCode && stemScore >= 0.70 -> 0.78 + nameScore * 0.20
            sameMunicipality && stemScore >= 0.92 -> 0.90 + stemScore * 0.08
            nameScore >= 0.82 && addressScore >= 0.82 -> nameScore * 0.55 + addressScore * 0.45
            addressScore >= 0.96 && nameScore >= 0.65 -> nameScore * 0.45 + addressScore * 0.55
            else -> null
        }
    }

    private fun comparableName(value: String, denominationId: String): String {
        var result = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("^[（(]?宗教法人[）)]?\\s*"), "")
            .trim()
        result = when (denominationId) {
            "UCCJ" -> result.replace(Regex("日本(?:基督|キリスト|基督[（(]キリスト[）)])教団"), "")
            "JBC" -> result.replace("日本バプテスト連盟", "")
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
            determinations = determinations.filterNot { it.field == "denominationId" } + determination,
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

    private data class OfficialEntry(
        val key: String,
        val list: OfficialDenominationChurchList,
        val church: OfficialDenominationChurch,
    )

    private data class CatalogOfficialMatch(
        val churchIndex: Int,
        val entry: OfficialEntry,
        val score: Double,
    )
}

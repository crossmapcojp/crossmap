package jp.co.crossmap.crawl

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.LocalizedName
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object GoogleSavedPlacesLists {
    const val CHURCH = "教会"
    const val CATHOLIC_CHURCH = "カトリック教会"
    const val CATHOLIC_DENOMINATION_ID = "CATHOLIC_JP"

    val DEFAULT: Set<String> = setOf(CHURCH, CATHOLIC_CHURCH)

    fun deterministicDenominationId(sourceLists: Collection<String>): String? =
        CATHOLIC_DENOMINATION_ID.takeIf { CATHOLIC_CHURCH in sourceLists }
}

/** Raw, stable seed data read from a Google Takeout Saved Places list. */
@Serializable
data class GoogleSavedPlaceSeed(
    val id: String,
    val googleCid: String,
    val title: String,
    val titleLanguages: List<String> = emptyList(),
    val japaneseName: String? = null,
    val latinName: String? = null,
    val localizedNames: List<LocalizedName> = emptyList(),
    val nameComponents: List<MultilingualNameComponent> = emptyList(),
    val namePattern: ChurchNamePattern = ChurchNamePattern.SINGLE_NAME,
    val googleMapsUrl: String,
    val sourceLists: List<String>,
    val note: String? = null,
    val comment: String? = null,
)

@Serializable
data class GoogleSavedPlacesSeedError(
    val sourceFile: String,
    val rowNumber: Int,
    val title: String? = null,
    val message: String,
)

@Serializable
data class GoogleSavedPlacesSeedReport(
    val filesRead: Int,
    val rowsRead: Int,
    val seedsWritten: Int,
    val duplicatesMerged: Int,
    val namePatternCounts: Map<String, Int> = emptyMap(),
    val languageCounts: Map<String, Int> = emptyMap(),
    val errors: List<GoogleSavedPlacesSeedError>,
)

/**
 * Reads Google Takeout Saved Places CSV files without depending on the old gmap project.
 * The output deliberately remains raw: later Google-place resolution supplies coordinates,
 * address, website and other fields required by a canonical ChurchRecord.
 *
 * @see jp.co.crossmap.crawl.ReadGoogleSavedPlaces for the CLI command that supplies
 * `inputDirectory` via the `--input` option (e.g. `--input Takeout/Saved`).
 */
class GoogleSavedPlacesSeedReader(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    fun readDirectory(
        inputDirectory: Path,
        output: Path,
        includedLists: Set<String>? = null,
        excludedUrls: Set<String> = emptySet(),
    ): GoogleSavedPlacesSeedReport {
        require(Files.isDirectory(inputDirectory)) { "Saved Places input is not a directory: $inputDirectory" }
        val files = Files.list(inputDirectory).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && it.extension.equals("csv", ignoreCase = true) &&
                    (includedLists == null || it.nameWithoutExtension in includedLists)
            }
                .sorted()
                .toList()
        }
        require(files.isNotEmpty()) { "No Saved Places CSV files found in $inputDirectory" }

        var rowsRead = 0
        var duplicates = 0
        val errors = mutableListOf<GoogleSavedPlacesSeedError>()
        val seeds = linkedMapOf<String, GoogleSavedPlaceSeed>()

        files.forEach { source ->
            val sourceList = source.nameWithoutExtension
            val rows = Csv.read(Files.readString(source))
            if (rows.isEmpty()) return@forEach
            val header = rows.first().mapIndexed { index, value -> normalizeHeader(value) to index }.toMap()
            val titleIndex = header.findColumn("タイトル", "title")
            val urlIndex = header.findColumn("url")
            val noteIndex = header.findOptionalColumn("メモ", "note")
            val commentIndex = header.findOptionalColumn("コメント", "comment")

            rows.drop(1).forEachIndexed { rowIndex, columns ->
                if (columns.all(String::isBlank)) return@forEachIndexed
                rowsRead++
                val title = columns.valueAt(titleIndex).trim()
                val url = columns.valueAt(urlIndex).trim()
                if (url in excludedUrls) return@forEachIndexed
                runCatching {
                    require(title.isNotBlank()) { "Saved place title is blank" }
                    val cid = googleCid(url)
                    val candidate = GoogleSavedPlaceSeed(
                        id = "google:$cid",
                        googleCid = cid,
                        title = title,
                        titleLanguages = ChurchTitleLanguageDetector.detect(title),
                        googleMapsUrl = url,
                        sourceLists = listOf(sourceList),
                        note = columns.optionalValueAt(noteIndex),
                        comment = columns.optionalValueAt(commentIndex),
                    )
                    val existing = seeds[cid]
                    if (existing == null) {
                        seeds[cid] = candidate
                    } else {
                        duplicates++
                        seeds[cid] = existing.copy(
                            titleLanguages = (existing.titleLanguages + candidate.titleLanguages).distinct().sorted(),
                            sourceLists = (existing.sourceLists + sourceList).distinct().sorted(),
                            note = existing.note ?: candidate.note,
                            comment = existing.comment ?: candidate.comment,
                        )
                    }
                }.onFailure { failure ->
                    errors += GoogleSavedPlacesSeedError(
                        sourceFile = source.fileName.toString(),
                        rowNumber = rowIndex + 2,
                        title = title.ifBlank { null },
                        message = failure.message ?: failure::class.simpleName.orEmpty(),
                    )
                }
            }
        }

        Files.createDirectories(output.parent)
        val ordered = seeds.values.sortedWith(compareBy(GoogleSavedPlaceSeed::title, GoogleSavedPlaceSeed::googleCid))
        Files.writeString(output, json.encodeToString(ordered))
        return GoogleSavedPlacesSeedReport(
            filesRead = files.size,
            rowsRead = rowsRead,
            seedsWritten = ordered.size,
            duplicatesMerged = duplicates,
            namePatternCounts = emptyMap(),
            languageCounts = ordered.flatMap { it.titleLanguages.distinct() }.groupingBy { it }.eachCount().toSortedMap(),
            errors = errors,
        )
    }

    companion object {
        val GMAP_DEFAULT_LISTS: Set<String> = GoogleSavedPlacesLists.DEFAULT

        fun readExcludedUrls(files: List<Path>): Set<String> = files
            .filter(Files::isRegularFile)
            .flatMap { file ->
                val rows = Csv.read(Files.readString(file))
                if (rows.isEmpty()) emptyList() else {
                    val header = rows.first().mapIndexed { index, value -> normalizeHeader(value) to index }.toMap()
                    val urlIndex = header.findColumn("url")
                    rows.drop(1).map { it.valueAt(urlIndex).trim() }.filter(String::isNotBlank)
                }
            }
            .toSet()

        fun googleCid(url: String): String {
            require(url.isNotBlank()) { "Google Maps URL is blank" }
            Regex("""(?:!1s|/)(?:0x[0-9a-fA-F]+):(0x[0-9a-fA-F]+)(?:[!/?#]|$)""")
                .find(url)
                ?.groupValues
                ?.get(1)
                ?.removePrefix("0x")
                ?.let { return BigInteger(it, 16).toString() }
            Regex("""[?&]cid=(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
            error("URL does not contain a Google CID: $url")
        }

        private fun normalizeHeader(value: String): String = value.removePrefix("\uFEFF").trim().lowercase()
        private fun Map<String, Int>.findColumn(vararg names: String): Int =
            findOptionalColumn(*names) ?: error("Required CSV column is missing: ${names.joinToString(" or ")}")
        private fun Map<String, Int>.findOptionalColumn(vararg names: String): Int? =
            names.firstNotNullOfOrNull { this[normalizeHeader(it)] }
        private fun List<String>.valueAt(index: Int): String = getOrElse(index) { "" }
        private fun List<String>.optionalValueAt(index: Int?): String? =
            index?.let { valueAt(it) }?.trim()?.ifBlank { null }
    }

    private object Csv {
        fun read(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var index = 0
            fun finishField() { row += field.toString(); field.setLength(0) }
            fun finishRow() { finishField(); rows += row; row = mutableListOf() }
            while (index < text.length) {
                val char = text[index]
                when {
                    quoted && char == '"' && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                    char == '"' -> quoted = !quoted
                    !quoted && char == ',' -> finishField()
                    !quoted && (char == '\n' || char == '\r') -> {
                        if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                        finishRow()
                    }
                    else -> field.append(char)
                }
                index++
            }
            require(!quoted) { "CSV ended inside a quoted field" }
            if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
            return rows
        }
    }
}

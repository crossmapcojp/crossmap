package jp.co.crossmap.crawl

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GeoNameCacheReport(
    val sourceRowsRead: Long,
    val japanRowsRetained: Long,
    val japaneseAliases: Int,
    val ambiguousAliasesResolved: Int,
    val cleanup: JapaneseGeoNameCleanupReport = JapaneseGeoNameCleanupReport(0, 0, 0, 0, 0),
)

@Serializable
data class GeoNameAlternateNamesReport(
    val alternateRowsRead: Long,
    val matchedAlternateRows: Long,
    val translatedAliases: Map<String, Int>,
)

data class DetectedGeoName(
    val japaneseName: String,
    val englishName: String,
    val startIndex: Int,
    val endIndex: Int,
)

enum class JapaneseGeoNameRejectionReason {
    REVIEWED_CHURCH_NAME,
    KATAKANA_ONLY,
    ADDRESS_BLOCK,
}

@Serializable
data class JapaneseGeoNameCleanupReport(
    val inputNames: Int,
    val retainedNames: Int,
    val reviewedChurchNamesRemoved: Int,
    val katakanaOnlyNamesRemoved: Int,
    val addressBlocksRemoved: Int,
) {
    val removedNames: Int get() = inputNames - retainedNames
}

/** Removes GeoNames aliases that are unsafe or unhelpful as Japanese church-search locations. */
class JapaneseGeoNameCleaner(
    private val reviewedChurchNames: Set<String> = emptySet(),
) {
    fun rejectionReason(value: String): JapaneseGeoNameRejectionReason? {
        val name = value.trim()
        return when {
            name in reviewedChurchNames -> JapaneseGeoNameRejectionReason.REVIEWED_CHURCH_NAME
            KATAKANA_ONLY.matches(name) -> JapaneseGeoNameRejectionReason.KATAKANA_ONLY
            ADDRESS_BLOCK.matches(name) -> JapaneseGeoNameRejectionReason.ADDRESS_BLOCK
            else -> null
        }
    }

    fun isUsable(value: String): Boolean = rejectionReason(value) == null

    fun report(values: Iterable<String>): JapaneseGeoNameCleanupReport {
        val names = values.map(String::trim).filter(String::isNotBlank).distinct()
        val reasons = names.mapNotNull(::rejectionReason)
        return JapaneseGeoNameCleanupReport(
            inputNames = names.size,
            retainedNames = names.size - reasons.size,
            reviewedChurchNamesRemoved = reasons.count { it == JapaneseGeoNameRejectionReason.REVIEWED_CHURCH_NAME },
            katakanaOnlyNamesRemoved = reasons.count { it == JapaneseGeoNameRejectionReason.KATAKANA_ONLY },
            addressBlocksRemoved = reasons.count { it == JapaneseGeoNameRejectionReason.ADDRESS_BLOCK },
        )
    }

    companion object {
        private val KATAKANA_ONLY = Regex("""^[ァ-ヺヽヾー・]+$""")
        private val ADDRESS_BLOCK = Regex("""^[〇一二三四五六七八九十百千万0-9０-９]+丁目$""")

        fun fromCsv(path: Path): JapaneseGeoNameCleaner {
            if (!Files.isRegularFile(path)) return JapaneseGeoNameCleaner()
            val names = Files.readAllLines(path)
                .map { it.substringBefore(',').trim().trim('"') }
                .filter(String::isNotBlank)
                .toSet()
            return JapaneseGeoNameCleaner(names)
        }
    }
}

/** Prepares the official Japan dump and builds a deterministic Japanese-name to official ASCII-name lexicon. */
class GeoName(
    private val json: Json = Json { prettyPrint = true },
    private val cleaner: JapaneseGeoNameCleaner = JapaneseGeoNameCleaner(),
) {
    /** Downloads and extracts the official Japan dump only when JP.txt is absent. */
    fun ensureOfficialJapanDump(
        japanText: Path,
        zipUrl: URI = OFFICIAL_JAPAN_ZIP,
        openZipStream: (URI) -> InputStream = ::openHttpStream,
    ): Boolean {
        return ensureZipText(japanText, zipUrl, "JP.txt", openZipStream)
    }

    /** Downloads the language-tagged Japan alternate-name dump only when its JP.txt is absent. */
    fun ensureOfficialJapanAlternateNamesDump(
        alternateNamesText: Path,
        zipUrl: URI = OFFICIAL_JAPAN_ALTERNATE_NAMES_ZIP,
        openZipStream: (URI) -> InputStream = ::openHttpStream,
    ): Boolean = ensureZipText(alternateNamesText, zipUrl, "JP.txt", openZipStream)

    /** Downloads JMA's maintained multilingual municipality dictionary only when it is absent. */
    fun ensureJmaCityDictionary(
        destination: Path,
        sourceUrl: URI = JMA_CITY_DICTIONARY,
        openStream: (URI) -> InputStream = ::openHttpStream,
    ): Boolean {
        if (Files.isRegularFile(destination)) return false
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".jma-city-", ".json")
        try {
            openStream(sourceUrl).use { input -> Files.newOutputStream(temporary).use(input::copyTo) }
            val parsed = json.parseToJsonElement(Files.readString(temporary)).jsonObject
            require(parsed.isNotEmpty()) { "JMA city dictionary is empty: $sourceUrl" }
            runCatching { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
            return true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Converts JMA field names and municipality suffixes to Crossmap's language-keyed lexicon. */
    fun readJmaMultilingualLexicon(path: Path): Map<String, Map<String, String>> {
        if (!Files.isRegularFile(path)) return emptyMap()
        val result = linkedMapOf<String, MutableMap<String, String>>()
        json.parseToJsonElement(Files.readString(path)).jsonObject.values.forEach { element ->
            val city = element.jsonObject
            val japanese = city["japanese"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (japanese.isBlank() || !cleaner.isUsable(japanese)) return@forEach
            val translations = JMA_LANGUAGE_FIELDS.mapNotNull { (language, field) ->
                city[field]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let { language to it }
            }.toMap()
            if (translations.isEmpty()) return@forEach
            result.getOrPut(japanese) { linkedMapOf() }.putAll(translations)
            val shortJapanese = japanese.stripAdministrativeSuffix()
            if (shortJapanese != japanese && shortJapanese.length >= 2 && cleaner.isUsable(shortJapanese)) {
                val shortTranslations = translations.mapValues { (language, value) ->
                    value.stripTranslatedAdministrativeAffix(language)
                }
                result.getOrPut(shortJapanese) { linkedMapOf() }.putAll(shortTranslations)
            }
        }
        return result.mapValues { (_, translations) -> translations.toSortedMap() }.toSortedMap()
    }

    fun mergeMultilingualLexicons(
        base: Map<String, Map<String, String>>,
        additional: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> = buildMap {
        (base.keys + additional.keys).filter(cleaner::isUsable).sorted().forEach { japanese ->
            put(japanese, (base[japanese].orEmpty() + additional[japanese].orEmpty()).toSortedMap())
        }
    }

    private fun ensureZipText(
        destination: Path,
        zipUrl: URI,
        expectedFileName: String,
        openZipStream: (URI) -> InputStream,
    ): Boolean {
        if (Files.isRegularFile(destination)) return false
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".$expectedFileName-", ".txt")
        try {
            var extracted = false
            openZipStream(zipUrl).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.substringAfterLast('/') == expectedFileName) {
                            Files.newOutputStream(temporary).use(zip::copyTo)
                            extracted = true
                            break
                        }
                    }
                }
            }
            check(extracted) { "GeoNames archive does not contain $expectedFileName: $zipUrl" }
            runCatching {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun buildJapanAlternateNamesCache(
        japanText: Path,
        alternateNamesText: Path,
        multilingualLexiconJson: Path,
    ): GeoNameAlternateNamesReport {
        require(Files.isRegularFile(japanText)) { "Official GeoNames JP.txt does not exist: $japanText" }
        require(Files.isRegularFile(alternateNamesText)) {
            "Official GeoNames alternate names JP.txt does not exist: $alternateNamesText"
        }
        data class Entity(val aliases: List<String>, val english: String?, val rank: Long)
        data class RankedName(val name: String, val score: Long)

        val entities = linkedMapOf<String, Entity>()
        Files.newBufferedReader(japanText).useLines { lines ->
            lines.forEach { line ->
                val fields = parseDelimitedLine(line, '\t')
                if (fields.size < 19 || fields[8] != "JP") return@forEach
                val featureClass = fields[6]
                val featureCode = fields[7]
                if (featureClass !in setOf("A", "P") &&
                    !(featureClass == "S" && featureCode in CHURCH_RELATED_FEATURE_CODES)
                ) return@forEach
                val candidate = Candidate(fields[2].sanitizeAsciiName().orEmpty(), fields[14].toLongOrNull() ?: 0L, featureClass, featureCode)
                val aliases = buildList {
                    add(fields[1])
                    addAll(fields[3].split(Regex("""[;,]""")))
                }.map(String::trim).filter(::containsJapanese).flatMap { alias ->
                    listOf(alias, alias.stripAdministrativeSuffix()).distinct()
                }.filter { it.length >= 2 && cleaner.isUsable(it) }.distinct()
                if (aliases.isNotEmpty()) entities[fields[0]] = Entity(aliases, fields[2].sanitizeAsciiName(), candidate.rank)
            }
        }

        val ranked = linkedMapOf<String, MutableMap<String, RankedName>>()
        entities.values.forEach { entity ->
            entity.english?.let { english ->
                entity.aliases.forEach { alias -> ranked.getOrPut(alias) { linkedMapOf() }["en"] = RankedName(english, entity.rank) }
            }
        }
        var rowsRead = 0L
        var matchedRows = 0L
        Files.newBufferedReader(alternateNamesText).useLines { lines ->
            lines.forEach { line ->
                rowsRead++
                val fields = parseDelimitedLine(line, '\t')
                if (fields.size < 4) return@forEach
                val entity = entities[fields[1]] ?: return@forEach
                val language = fields[2].substringBefore('-').lowercase()
                if (language !in MULTILINGUAL_TARGET_LANGUAGES) return@forEach
                val name = fields[3].trim()
                if (name.isBlank() || fields.getOrNull(7) == "1") return@forEach
                matchedRows++
                val score = entity.rank +
                    (if (fields.getOrNull(4) == "1") 1_000_000_000_000L else 0L) +
                    (if (fields.getOrNull(5) == "1") 100_000_000_000L else 0L) -
                    (if (fields.getOrNull(6) == "1") 10_000_000_000L else 0L)
                entity.aliases.forEach { alias ->
                    val translations = ranked.getOrPut(alias) { linkedMapOf() }
                    if (score > (translations[language]?.score ?: Long.MIN_VALUE)) {
                        translations[language] = RankedName(name, score)
                    }
                }
            }
        }
        val lexicon = ranked.mapValues { (_, names) -> names.mapValues { it.value.name }.toSortedMap() }.toSortedMap()
        Files.createDirectories(multilingualLexiconJson.parent)
        val temporary = Files.createTempFile(multilingualLexiconJson.parent, ".geoname-multilingual-", ".json")
        Files.writeString(temporary, json.encodeToString<Map<String, Map<String, String>>>(lexicon))
        Files.move(temporary, multilingualLexiconJson, StandardCopyOption.REPLACE_EXISTING)
        return GeoNameAlternateNamesReport(
            alternateRowsRead = rowsRead,
            matchedAlternateRows = matchedRows,
            translatedAliases = MULTILINGUAL_TARGET_LANGUAGES.associateWith { language ->
                lexicon.values.count { language in it }
            },
        )
    }

    fun readMultilingualLexicon(path: Path): Map<String, Map<String, String>> =
        if (Files.isRegularFile(path)) {
            json.decodeFromString<Map<String, Map<String, String>>>(Files.readString(path))
                .filterKeys(cleaner::isUsable)
        } else {
            emptyMap()
        }

    fun writeMultilingualLexicon(path: Path, lexicon: Map<String, Map<String, String>>) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".geoname-multilingual-", ".json")
        Files.writeString(
            temporary,
            json.encodeToString<Map<String, Map<String, String>>>(
                lexicon.filterKeys(cleaner::isUsable).toSortedMap(),
            ),
        )
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private data class Candidate(
        val english: String,
        val population: Long,
        val featureClass: String,
        val featureCode: String,
    ) {
        val rank: Long
            get() = population + when (featureClass) {
                "P" -> 10_000_000_000L
                "A" -> 5_000_000_000L
                else -> 0L
            } + when (featureCode) {
                "PPLC" -> 2_000_000_000L
                "ADM1", "ADM2", "ADM3", "ADM4" -> 1_000_000_000L
                else -> 0L
            }
    }

    fun buildJapanCache(japanText: Path, japanCsv: Path, lexiconJson: Path): GeoNameCacheReport {
        require(Files.isRegularFile(japanText)) { "Official GeoNames JP.txt does not exist: $japanText" }
        Files.createDirectories(japanCsv.parent)
        Files.createDirectories(lexiconJson.parent)
        val candidates = linkedMapOf<String, MutableList<Candidate>>()
        val rawAliases = linkedSetOf<String>()
        var rowsRead = 0L
        var japanRows = 0L
        Files.newBufferedReader(japanText).use { reader ->
            Files.newBufferedWriter(japanCsv).use { writer ->
                val firstLine = reader.readLine() ?: error("GeoNames CSV is empty")
                val delimiter = if ('\t' in firstLine) '\t' else ','
                val firstIsHeader = firstLine.startsWith("geonameid", ignoreCase = true)
                if (firstIsHeader) writer.appendLine(firstLine)
                val lines = sequence {
                    if (!firstIsHeader) yield(firstLine)
                    yieldAll(reader.lineSequence())
                }
                lines.forEach { line ->
                    rowsRead++
                    val fields = parseDelimitedLine(line, delimiter)
                    if (fields.size < 19 || fields[8] != "JP") return@forEach
                    writer.appendLine(line)
                    japanRows++
                    val featureClass = fields[6]
                    val featureCode = fields[7]
                    if (featureClass !in setOf("A", "P") &&
                        !(featureClass == "S" && featureCode in CHURCH_RELATED_FEATURE_CODES)
                    ) return@forEach
                    val english = fields[2].sanitizeAsciiName() ?: return@forEach
                    val candidate = Candidate(
                        english,
                        fields[14].toLongOrNull() ?: 0L,
                        featureClass,
                        featureCode,
                    )
                    val aliases = buildList {
                        add(fields[1])
                        addAll(fields[3].split(Regex("""[;,]""")))
                    }.map(String::trim).filter(::containsJapanese).flatMap { alias ->
                        listOf(alias, alias.stripAdministrativeSuffix()).distinct()
                    }.filter { it.length >= 2 }.distinct()
                    rawAliases += aliases
                    aliases.filter(cleaner::isUsable).forEach { alias ->
                        candidates.getOrPut(alias) { mutableListOf() }.add(candidate)
                    }
                }
            }
        }
        var ambiguous = 0
        val lexicon = candidates.mapValues { (_, values) ->
            val distinct = values.distinctBy(Candidate::english)
            if (distinct.size > 1) ambiguous++
            distinct.maxWith(compareBy<Candidate> { it.rank }.thenByDescending { it.english.length }).english
        }.toSortedMap()
        val temporary = Files.createTempFile(lexiconJson.parent, ".geoname-lexicon-", ".json")
        Files.writeString(temporary, json.encodeToString<Map<String, String>>(lexicon))
        Files.move(temporary, lexiconJson, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return GeoNameCacheReport(rowsRead, japanRows, lexicon.size, ambiguous, cleaner.report(rawAliases))
    }

    fun readLexicon(path: Path): Map<String, String> =
        if (Files.isRegularFile(path)) {
            json.decodeFromString<Map<String, String>>(Files.readString(path)).filterKeys(cleaner::isUsable)
        } else {
            emptyMap()
        }

    /** Finds longest non-overlapping Japanese aliases and returns their authoritative ASCII names. */
    fun detectAndTranslate(text: String, lexicon: Map<String, String>): List<DetectedGeoName> {
        val candidates = lexicon.entries.asSequence().flatMap { (japanese, english) ->
            Regex(Regex.escape(japanese)).findAll(text).map { match ->
                DetectedGeoName(japanese, english, match.range.first, match.range.last + 1)
            }
        }.sortedWith(compareBy<DetectedGeoName> { it.startIndex }.thenByDescending { it.endIndex - it.startIndex })
        val accepted = mutableListOf<DetectedGeoName>()
        candidates.forEach { candidate ->
            if (accepted.none { candidate.startIndex < it.endIndex && candidate.endIndex > it.startIndex }) {
                accepted += candidate
            }
        }
        return accepted.sortedBy(DetectedGeoName::startIndex)
    }

    fun translateJapaneseName(value: String, lexicon: Map<String, String>): String? = lexicon[value]

    internal fun parseCsvLine(line: String): List<String> = parseDelimitedLine(line, ',')

    internal fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                    value.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == delimiter && !quoted -> {
                    fields += value.toString()
                    value.clear()
                }
                else -> value.append(character)
            }
            index++
        }
        fields += value.toString()
        return fields
    }

    private fun String.sanitizeAsciiName(): String? = Normalizer.normalize(this, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^A-Za-z0-9 .'-]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .takeIf { it.any(Char::isLetter) }

    private fun String.stripAdministrativeSuffix(): String =
        removeSuffix("都").removeSuffix("道").removeSuffix("府").removeSuffix("県")
            .removeSuffix("市").removeSuffix("区").removeSuffix("町").removeSuffix("村")

    private fun String.stripTranslatedAdministrativeAffix(language: String): String = when (language) {
        "en" -> replace(Regex("""\s+(City|Ward|Town|Village)$""", RegexOption.IGNORE_CASE), "")
        "ko" -> replace(Regex("""\s+(시|구|정|촌)$"""), "")
        "pt" -> replace(Regex("""^(cidade|bairro|vila|aldeia)\s+de\s+""", RegexOption.IGNORE_CASE), "")
        "id" -> replace(Regex("""^(kota|distrik|desa)\s+""", RegexOption.IGNORE_CASE), "")
        else -> this
    }.trim()

    private fun containsJapanese(value: String): Boolean =
        value.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }

    private fun openHttpStream(uri: URI): InputStream {
        val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(
            HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        check(response.statusCode() in 200..299) {
            response.body().close()
            "GeoNames download failed with HTTP ${response.statusCode()}: $uri"
        }
        return response.body()
    }

    private companion object {
        val OFFICIAL_JAPAN_ZIP: URI = URI.create("https://download.geonames.org/export/dump/JP.zip")
        val OFFICIAL_JAPAN_ALTERNATE_NAMES_ZIP: URI =
            URI.create("https://download.geonames.org/export/dump/alternatenames/JP.zip")
        val JMA_CITY_DICTIONARY: URI = URI.create("https://www.data.jma.go.jp/multi/data/dictionary/city.json")
        val JMA_LANGUAGE_FIELDS = linkedMapOf(
            "en" to "english",
            "ko" to "korean",
            "pt" to "portuguese",
            "id" to "indonesian",
            "vi" to "vietnamese",
        )
        val MULTILINGUAL_TARGET_LANGUAGES = listOf("en", "ko", "pt", "id", "vi")
        val CHURCH_RELATED_FEATURE_CODES = setOf("CH", "MSTY", "CTRR")
    }
}

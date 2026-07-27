package jp.co.crossmap.crawl

import java.math.BigInteger
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.time.Instant
import jp.co.crossmap.ChurchWebsitePolicy
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.JapaneseAddressNormalizer
import jp.co.crossmap.LightPanda
import jp.co.crossmap.LocalizedName
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

object GoogleSavedPlacesLists {
    const val CHURCH = "教会"
    const val CATHOLIC_CHURCH = "カトリック教会"
    const val CATHOLIC_DENOMINATION_ID = "CATHOLIC_JP"

    val DEFAULT: Set<String> = setOf(CHURCH, CATHOLIC_CHURCH)

    fun deterministicDenominationId(sourceLists: Collection<String>): String? =
        CATHOLIC_DENOMINATION_ID.takeIf { CATHOLIC_CHURCH in sourceLists }
}

@Serializable
data class GoogleSavedPlaceCrawl(
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
data class GoogleSavedPlacesCrawlError(
    val sourceFile: String,
    val rowNumber: Int,
    val title: String? = null,
    val message: String,
)

@Serializable
data class GoogleSavedPlacesCrawlReport(
    val filesRead: Int,
    val rowsRead: Int,
    val seedsWritten: Int,
    val duplicatesMerged: Int,
    val namePatternCounts: Map<String, Int> = emptyMap(),
    val languageCounts: Map<String, Int> = emptyMap(),
    val errors: List<GoogleSavedPlacesCrawlError>,
)

@Serializable
data class GooglePlaceChurchCandidate(
    val id: String,
    val googleCid: String,
    val name: String,
    val titleLanguages: List<String> = emptyList(),
    val latinName: String? = null,
    val localizedNames: List<LocalizedName> = emptyList(),
    val nameComponents: List<MultilingualNameComponent> = emptyList(),
    val namePattern: ChurchNamePattern = ChurchNamePattern.SINGLE_NAME,
    val address: String,
    val location: GeoPoint,
    val websiteUrl: String,
    val category: String? = null,
    val denominationHint: String? = null,
    val sourceLists: List<String>,
    val resolvedAt: String,
)

@Serializable
data class GoogleMapsResolutionError(val id: String, val name: String, val message: String)

@Serializable
data class GoogleMapsResolutionReport(
    val seeds: Int,
    val candidates: Int,
    val cacheHits: Int,
    val fetched: Int,
    val catholicNonChurchesFiltered: Int,
    val namePatternCounts: Map<String, Int> = emptyMap(),
    val languageCounts: Map<String, Int> = emptyMap(),
    val localizedNameCounts: Map<String, Int> = emptyMap(),
    val nameComponentRoleCounts: Map<String, Int> = emptyMap(),
    val candidatesWithUnresolvedNameComponents: Int = 0,
    val errors: List<GoogleMapsResolutionError>,
)

data class GoogleMapsPage(val html: String, val cacheHit: Boolean)

fun interface GoogleMapsPageSource {
    fun load(seed: GoogleSavedPlaceCrawl): GoogleMapsPage
}

class CachedGoogleMapsPageSource(
    private val httpFetcher: HttpFetcher,
    private val legacyCacheDir: Path? = null,
) : GoogleMapsPageSource {
    override fun load(seed: GoogleSavedPlaceCrawl): GoogleMapsPage {
        val pageCid = if (seed.googleCid == "3576720766476721565") "6907614827878617439" else seed.googleCid
        if (legacyCacheDir != null) {
            val legacyFile = legacyCacheDir.resolve("pages/$pageCid.html")
            if (Files.isRegularFile(legacyFile)) {
                return GoogleMapsPage(Files.readString(legacyFile), cacheHit = true)
            }
        }
        val url = "https://www.google.com/maps?cid=$pageCid"
        val fetched = httpFetcher.fetch(url)
        val html = fetched.html
        return GoogleMapsPage(html, cacheHit = fetched.via == "cache")
    }
}

class GoogleMapsPlaceParser(
    private val multilingualNameLocalizer: MultilingualChurchNameLocalizer? = null,
    private val websitePolicy: ChurchWebsitePolicy = ChurchWebsitePolicy(emptySet()),
) {
    private val nameDecomposer = ChurchNameDecomposer()
    fun parse(seed: GoogleSavedPlaceCrawl, html: String, now: String = Instant.now().toString()): GooglePlaceChurchCandidate {
        val document = Jsoup.parse(html)
        val isGoogleMapsPage = html.contains("google.com/maps/preview/place/") ||
            html.contains("Google Maps") ||
            html.contains("Google マップ") ||
            document.selectFirst("button[data-item-id=address], div[data-item-id=address], a[data-item-id=authority], h1.DUwDvf") != null
        require(isGoogleMapsPage) { "Not a Google Maps place page" }

        val ogTitleEl = document.selectFirst("meta[property=og:title]")
        val ogTitle = if (ogTitleEl != null) ogTitleEl.attr("content") else ""
        val titleParts = if (ogTitle.contains(" · ")) ogTitle.split(" · ", limit = 2) else emptyList()
        val nameFromOgTitle = titleParts.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && it != "Google Maps" && it != "Google マップ" }
        val h1El = document.selectFirst("h1.DUwDvf")
        val nameFromH1 = if (h1El != null) h1El.text().trim().ifBlank { null } else null
        val nameFromDocTitle = document.title()
            .removeSuffix(" - Google マップ")
            .removeSuffix(" - Google Maps")
            .trim()
            .takeIf { it.isNotBlank() && it != "Google Maps" && it != "Google マップ" }
        val bundleIdEl = document.selectFirst("[data-bundle-id]")
        val nameFromBundleId = if (bundleIdEl != null) bundleIdEl.attr("data-bundle-id").trim().ifBlank { null } else null

        val name = nameFromOgTitle
            ?: nameFromH1
            ?: nameFromDocTitle
            ?: nameFromBundleId
            ?: seed.title.trim()
        require(name.isNotBlank()) { "Google place name is blank" }

        val preview = PREVIEW_PLACE.find(html)
        var latitude: Double? = null
        var longitude: Double? = null
        var addressFromPath = ""

        if (preview != null) {
            val decodedPlacePath = URLDecoder.decode(preview.groupValues[1].replace("+", "%20"), Charsets.UTF_8)
            addressFromPath = decodedPlacePath.removeSuffix(name).trim().trimEnd(',')
            latitude = preview.groupValues[2].toDoubleOrNull()
            longitude = preview.groupValues[3].toDoubleOrNull()
        }

        if (latitude == null || longitude == null) {
            val altCoords = ALT_COORDINATES_1.find(html) ?: ALT_COORDINATES_2.find(html)
            if (altCoords != null) {
                latitude = altCoords.groupValues[1].toDoubleOrNull()
                longitude = altCoords.groupValues[2].toDoubleOrNull()
            }
        }
        requireNotNull(latitude) { "Google preview URL or HTML has no latitude" }
        requireNotNull(longitude) { "Google preview URL or HTML has no longitude" }

        val addressEl = document.selectFirst("[data-item-id=address]")
        val addressFromDom = if (addressEl != null) {
            val labelStr = addressEl.attr("aria-label")
            val label = labelStr.removePrefix("住所:").removePrefix("Address:").trim()
            val subEl = addressEl.selectFirst(".Io6YTe")
            val textStr = if (subEl != null) subEl.text() else addressEl.text()
            val text = textStr.removePrefix("住所:").removePrefix("Address:").trim()
            if (text.isNotBlank()) text else label.ifBlank { null }
        } else null

        val explicitPostalMatch = EXPLICIT_POSTAL_ARRAY.find(html)?.groupValues?.get(1)?.trim()
        val postalMatch = POSTAL_CODE_ADDRESS.find(html)?.groupValues?.get(0)

        val rawAddress = titleParts.getOrNull(1)?.trim()?.ifBlank { null }
            ?: addressFromDom
            ?: explicitPostalMatch
            ?: postalMatch
            ?: addressFromPath.ifBlank { null }

        val address = GooglePlaceAddressNormalizer.normalize(rawAddress.orEmpty())
        require(address.isNotBlank()) { "Google place address is blank" }

        val website = websitePolicy.publicWebsiteUrl(extractWebsite(document, html), seed.googleCid, seed.id)
        val ogDescEl = document.selectFirst("meta[property=og:description]")
        val categoryFromOg = if (ogDescEl != null) {
            val content = ogDescEl.attr("content").trim()
            val extracted = if (content.contains(" · ")) content.substringAfter(" · ").trim() else content
            if (extracted.contains("Find local businesses") || extracted.contains("Google Maps") || extracted.contains("Google マップ")) {
                null
            } else {
                extracted.ifBlank { null }
            }
        } else null
        val catBtnEl = document.selectFirst("button[jsaction*='category']")
        val categoryFromBtn = if (catBtnEl != null) catBtnEl.text().trim().ifBlank { null } else null
        val category = categoryFromOg ?: categoryFromBtn

        val rawName = name.replace("\n", "").replace(Regex("""\s+"""), " ").trim()
        val savedTitle = seed.title.replace("\n", "").replace(Regex("""\s+"""), " ").trim()
        val localizationName = ChurchPublicNameNormalizer.normalize(savedTitle.ifBlank { rawName })
        val localized = multilingualNameLocalizer?.localize(localizationName, seed.titleLanguages, address)
        val decomposed = localized?.let {
            DecomposedChurchName(localizationName, it.japaneseName, it.latinName, it.localizedNames, it.pattern)
        } ?: nameDecomposer.decompose(localizationName)
        val seedHasRicherName = localized == null &&
            (seed.japaneseName != null || seed.latinName != null || seed.localizedNames.isNotEmpty()) &&
            (decomposed.pattern == ChurchNamePattern.SINGLE_NAME ||
                (decomposed.latinName != null && decomposed.latinName == seed.latinName))
        val japaneseName = if (seedHasRicherName) seed.japaneseName else decomposed.japaneseName
        val latinName = if (seedHasRicherName) seed.latinName else decomposed.latinName
        val localizedNames = if (seedHasRicherName) seed.localizedNames else decomposed.localizedNames
        return GooglePlaceChurchCandidate(
            id = seed.id,
            googleCid = seed.googleCid,
            name = japaneseName ?: ChurchPublicNameNormalizer.normalize(rawName),
            titleLanguages = seed.titleLanguages,
            latinName = latinName,
            localizedNames = localizedNames,
            nameComponents = localized?.components.orEmpty(),
            namePattern = decomposed.pattern,
            address = address,
            location = GeoPoint(latitude, longitude),
            websiteUrl = website,
            category = category,
            denominationHint = GoogleSavedPlacesLists.deterministicDenominationId(seed.sourceLists),
            sourceLists = seed.sourceLists,
            resolvedAt = now,
        )
    }

    private fun extractWebsite(document: org.jsoup.nodes.Document, html: String): String? {
        val authorityEl = document.selectFirst("a[data-item-id=authority][href]")
        if (authorityEl != null) {
            val href = authorityEl.attr("href")
            if (href.startsWith("http://") || href.startsWith("https://")) {
                return href
            }
        }
        return ENCODED_WEBSITE.find(html)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?.replace("\\u0026", "&")
    }

    companion object {
        private val PREVIEW_PLACE = Regex(
            """https://www\.google\.com/maps/preview/place/([^\"']+?)/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""",
        )
        private val ALT_COORDINATES_1 = Regex(
            """(?:/@|%40)(-?\d+\.\d+)(?:,|%2C)(-?\d+\.\d+)""",
        )
        private val ALT_COORDINATES_2 = Regex(
            """3d(-?\d+\.\d+)(?:!4d|%214d)(-?\d+\.\d+)""",
        )
        private val EXPLICIT_POSTAL_ARRAY = Regex(
            """\[\\?"(〒\d{3}-\d{4}\s+[^\\"'\n]+)\\?"\]""",
        )
        private val POSTAL_CODE_ADDRESS = Regex("""〒\d{3}-\d{4}\s+[^\<\"'\n\\]+""")
        private val ENCODED_WEBSITE = Regex(
            """/url\?q\\\\u003d(https?://.+?)\\\\u0026opi""",
        )
    }
}

internal object GooglePlaceAddressNormalizer {
    fun normalize(value: String): String {
        val compact = value.replace(Regex("""\s+"""), " ").trim()
        val inlineTrailingPlace = INLINE_TRAILING_PLACE.matchEntire(compact)
            ?.takeIf { CHURCH_ENTITY_NAME.containsMatchIn(it.groupValues[2]) }
        if (inlineTrailingPlace != null) return zenkakuStreetNumbers(inlineTrailingPlace.groupValues[1])
        val parsed = JapaneseAddressNormalizer.normalize(compact)
        val building = parsed.building
        val trailingPlaceName = building
            ?.takeIf { CHURCH_ENTITY_NAME.containsMatchIn(it) }
        val routeFreeBuilding = building?.replace(
            Regex("""^.*?(?:駅から)?徒歩[０-９0-9]+分\s*"""),
            "",
        )?.trim()
        val addressOnly = when {
            trailingPlaceName != null -> parsed.normalized.removeSuffix(building).trim()
            building != null && routeFreeBuilding != building -> listOf(
                parsed.normalized.removeSuffix(building).trim(),
                routeFreeBuilding,
            ).filterNotNull().filter(String::isNotBlank).joinToString(" ")
            else -> compact
        }
        return zenkakuStreetNumbers(addressOnly)
    }

    private fun zenkakuStreetNumbers(value: String): String {
        val postalPrefix = Regex("""^〒\d{3}-\d{4}\s*""").find(value)?.value.orEmpty()
        val streetAddress = value.removePrefix(postalPrefix)
            .map { character ->
                when (character) {
                    in '0'..'9' -> '０' + (character - '0')
                    '-' -> '−'
                    else -> character
                }
            }
            .joinToString("")
        return postalPrefix + streetAddress
    }

    private val CHURCH_ENTITY_NAME = Regex(
        "(?:キリスト|基督)?教(?:会|會)|基督会|チャペル|チャ[-ー]チ|聖堂|集会|小隊|伝[道導]所|聖公会",
    )
    private val INLINE_TRAILING_PLACE = Regex(
        """^((?:〒[０-９0-9]{3}-[０-９0-9]{4}\s*)?.*?(?:東京都|北海道|大阪府|京都府|.{2,3}県).+?[０-９0-9]+(?:丁目)?[０-９0-9−ー―‐‑–—ｰ-]*(?:番地?|番|号)?)\s+(.+)$""",
    )
}

internal object GooglePlaceChurchCandidatePolicy {
    fun isUsableChurchName(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
        return normalized.isNotBlank() && !Regex("""^〒?\d{3}-?\d{4}(?:\s|$)""").containsMatchIn(normalized)
    }
}

internal object GooglePlaceChurchNameNormalizer {
    fun normalize(value: String): String = ChurchPublicNameNormalizer.normalize(value)
        .replace("伝導所", "伝道所")
}

class GoogleSavedPlacesCrawler(
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
    private val resourcesRoot: Path? = null,
    private val inputDirectory: Path? = null,
) {

    fun readDirectory(
        resourcesRoot: Path,
        inputDirectory: Path,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
        includedLists: Set<String>? = null,
        concurrency: Int = 6,
        offline: Boolean = false,
        multilingualNameLocalizer: MultilingualChurchNameLocalizer? = null,
    ): GoogleSavedPlacesResolutionPipelineReport {
        val readReport = readDirectory(
            inputDirectory = inputDirectory,
            output = CrossmapPaths(resourcesRoot, cacheRoot).googleSavedPlaces.resolve("seeds.json"),
            includedLists = includedLists,
        )
        val resolutionReport = resolve(
            resourcesRoot = resourcesRoot,
            cacheRoot = cacheRoot,
            concurrency = concurrency,
            offline = offline,
            multilingualNameLocalizer = multilingualNameLocalizer,
        )
        return GoogleSavedPlacesResolutionPipelineReport(
            crawlReport = readReport,
            resolutionReport = resolutionReport,
        )
    }

    fun readDirectory(
        inputDirectory: Path,
        output: Path,
        includedLists: Set<String>? = null,
        excludedUrls: Set<String> = emptySet(),
    ): GoogleSavedPlacesCrawlReport {
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
        val errors = mutableListOf<GoogleSavedPlacesCrawlError>()
        val seeds = linkedMapOf<String, GoogleSavedPlaceCrawl>()

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
                    val candidate = GoogleSavedPlaceCrawl(
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
                    errors += GoogleSavedPlacesCrawlError(
                        sourceFile = source.fileName.toString(),
                        rowNumber = rowIndex + 2,
                        title = title.ifBlank { null },
                        message = failure.message ?: failure::class.simpleName.orEmpty(),
                    )
                }
            }
        }

        Files.createDirectories(output.parent)
        val ordered = seeds.values.sortedWith(compareBy(GoogleSavedPlaceCrawl::title, GoogleSavedPlaceCrawl::googleCid))
        Files.writeString(output, json.encodeToString(ordered))
        return GoogleSavedPlacesCrawlReport(
            filesRead = files.size,
            rowsRead = rowsRead,
            seedsWritten = ordered.size,
            duplicatesMerged = duplicates,
            namePatternCounts = emptyMap(),
            languageCounts = ordered.flatMap { it.titleLanguages.distinct() }.groupingBy { it }.eachCount().toSortedMap(),
            errors = errors,
        )
    }

    fun resolve(
        resourcesRoot: Path,
        cacheRoot: Path = CrossmapPaths.defaultCacheRoot(resourcesRoot),
        concurrency: Int = 6,
        offline: Boolean = false,
        multilingualNameLocalizer: MultilingualChurchNameLocalizer? = null,
        pageSource: GoogleMapsPageSource? = null,
        throttleMs: Long = 1000,
        playwright: PlaywrightBrowser? = null,
    ): GoogleMapsResolutionReport {
        val ownPlaywright = if (playwright == null && pageSource == null) PlaywrightBrowser() else null
        val effectivePlaywright = playwright ?: ownPlaywright
        try {
            return resolveInternal(resourcesRoot, cacheRoot, offline, multilingualNameLocalizer, pageSource, throttleMs, effectivePlaywright)
        } finally {
            ownPlaywright?.close()
        }
    }

    private fun resolveInternal(
        resourcesRoot: Path,
        cacheRoot: Path,
        offline: Boolean,
        multilingualNameLocalizer: MultilingualChurchNameLocalizer?,
        pageSource: GoogleMapsPageSource?,
        throttleMs: Long,
        playwright: PlaywrightBrowser?,
    ): GoogleMapsResolutionReport {
        val raw = CrossmapPaths(resourcesRoot, cacheRoot).googleSavedPlaces
        val seeds = json.decodeFromString<List<GoogleSavedPlaceCrawl>>(Files.readString(raw.resolve("seeds.json")))
        val excludedGooglePlaces = ExcludedGooglePlaces.load(resourcesRoot)
        val paths = CrossmapPaths(resourcesRoot, cacheRoot)
        val effectivePageSource = pageSource ?: CachedGoogleMapsPageSource(
            HttpFetcher(
                cacheDir = paths.webPages,
                playwright = playwright,
                manualCacheDir = paths.webPagesManual,
                cloudflareBlockedLog = paths.cloudflareBlockedLog,
            ),
            legacyCacheDir = paths.cacheRoot.resolve("google-maps-pages"),
        )
        val parser = GoogleMapsPlaceParser(
            multilingualNameLocalizer,
            ExcludedChurchListingDomains.policy(resourcesRoot),
        )
        val results = mutableListOf<Result>()
        var fetchCount = 0
        val total = seeds.size
        seeds.forEachIndexed { index, seed ->
            val excluded = ExcludedGooglePlaces.contains(excludedGooglePlaces, seed.id, seed.googleCid)
            if (excluded) {
                results.add(Result(filtered = true))
                println("[${index + 1}/$total] ${seed.title} (${seed.googleCid}) — excluded, skipped")
                return@forEachIndexed
            }
            val result = resolveOne(seed, effectivePageSource, parser)
            results.add(result)
            val status = when {
                result.error != null -> "ERROR: ${result.error.message}"
                result.cacheHit -> "parsed (cached)"
                result.fetched -> {
                    fetchCount++
                    "parsed (fetched)"
                }
                else -> "parsed"
            }
            val name = result.candidate?.name?.let { " → $it" } ?: ""
            println("[${index + 1}/$total] ${seed.title} (${seed.googleCid}) — $status$name")
            if (result.fetched && index < total - 1) {
                val delay = throttleMs + (Math.random() * throttleMs).toLong()
                Thread.sleep(delay)
            }
        }
        val candidates = results.mapNotNull(Result::candidate).sortedBy(GooglePlaceChurchCandidate::id)
        println("Done: ${candidates.size} candidates, $fetchCount fetched, ${results.count { it.cacheHit }} cached, ${results.count { it.filtered }} filtered, ${results.count { it.error != null }} errors")
        atomicWrite(raw.resolve("google-place-candidates.json"), json.encodeToString(candidates))
        val candidatesById = candidates.associateBy(GooglePlaceChurchCandidate::id)
        val enrichedSeeds = seeds.map { seed ->
            candidatesById[seed.id]?.let { candidate ->
                seed.copy(
                    japaneseName = candidate.name,
                    latinName = candidate.latinName,
                    localizedNames = candidate.localizedNames,
                    nameComponents = candidate.nameComponents,
                    namePattern = candidate.namePattern,
                )
            } ?: seed
        }
        atomicWrite(raw.resolve("seeds.json"), json.encodeToString(enrichedSeeds))
        val errors = results.mapNotNull(Result::error)
        val report = GoogleMapsResolutionReport(
            seeds = seeds.size,
            candidates = candidates.size,
            cacheHits = results.count { it.cacheHit },
            fetched = results.count { it.fetched },
            catholicNonChurchesFiltered = results.count { it.filtered },
            namePatternCounts = candidates.groupingBy { it.namePattern.name }.eachCount().toSortedMap(),
            languageCounts = candidates.flatMap { it.titleLanguages.distinct() }.groupingBy { it }.eachCount().toSortedMap(),
            localizedNameCounts = candidates.flatMap { candidate ->
                candidate.localizedNames.map { it.languageCode.substringBefore('-').lowercase() }.distinct()
            }.groupingBy { it }.eachCount().toSortedMap(),
            nameComponentRoleCounts = candidates.flatMap { candidate ->
                candidate.nameComponents.map { it.role.name }
            }.groupingBy { it }.eachCount().toSortedMap(),
            candidatesWithUnresolvedNameComponents = candidates.count { candidate ->
                candidate.nameComponents.any { it.role == MultilingualNameComponentRole.OTHER }
            },
            errors = errors,
        )
        atomicWrite(raw.resolve("google-place-resolution-report.json"), json.encodeToString(report))
        return report
    }

    private data class Result(
        val candidate: GooglePlaceChurchCandidate? = null,
        val cacheHit: Boolean = false,
        val fetched: Boolean = false,
        val filtered: Boolean = false,
        val error: GoogleMapsResolutionError? = null,
    )

    private fun resolveOne(seed: GoogleSavedPlaceCrawl, pageSource: GoogleMapsPageSource, parser: GoogleMapsPlaceParser): Result {
        val page = try {
            pageSource.load(seed)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Failed to fetch Google Maps page for place '${seed.title}' (CID: ${seed.googleCid}, URL: ${seed.googleMapsUrl}): ${error.message}",
                error,
            )
        }
        val candidate = try {
            parser.parse(seed, page.html)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Failed to parse Google Maps page for place '${seed.title}' (CID: ${seed.googleCid}, URL: ${seed.googleMapsUrl}): ${error.message}",
                error,
            )
        }
        return if (GoogleSavedPlacesLists.CATHOLIC_CHURCH in seed.sourceLists && !isCatholicChurchName(candidate.name)) {
            Result(cacheHit = page.cacheHit, fetched = !page.cacheHit, filtered = true)
        } else {
            Result(candidate = candidate, cacheHit = page.cacheHit, fetched = !page.cacheHit)
        }
    }

    private fun isCatholicChurchName(name: String): Boolean = !name.contains("宣教会") && (
        listOf("教会", "教会堂", "聖堂", "Church", "church", "天主堂", "天主堂)", "集会所", "教会（巡回）",
            "（サレジオ教会）", "(多治見修道院）", "教會", "大聖堂）", "礼拝堂", "伝道所")
            .any(name::endsWith) || name.startsWith("Igreja") || name.startsWith("Church of")
        )

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

        private fun atomicWrite(destination: Path, content: String) {
            Files.createDirectories(destination.parent)
            val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}-", ".tmp")
            Files.writeString(temporary, content)
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
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

@Serializable
data class GoogleSavedPlacesResolutionPipelineReport(
    val crawlReport: GoogleSavedPlacesCrawlReport,
    val resolutionReport: GoogleMapsResolutionReport,
)

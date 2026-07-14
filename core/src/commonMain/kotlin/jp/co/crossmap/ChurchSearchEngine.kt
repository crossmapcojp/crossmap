package jp.co.crossmap

import kotlinx.serialization.json.Json
import okio.Path
import org.gnit.lucenekmp.analysis.ja.JapaneseAnalyzer
import org.gnit.lucenekmp.document.LatLonPoint
import org.gnit.lucenekmp.index.StandardDirectoryReader
import org.gnit.lucenekmp.index.Term
import org.gnit.lucenekmp.queryparser.classic.MultiFieldQueryParser
import org.gnit.lucenekmp.queryparser.classic.QueryParser
import org.gnit.lucenekmp.search.BooleanClause
import org.gnit.lucenekmp.search.BooleanQuery
import org.gnit.lucenekmp.search.IndexSearcher
import org.gnit.lucenekmp.search.MatchAllDocsQuery
import org.gnit.lucenekmp.search.Query
import org.gnit.lucenekmp.search.TermQuery
import org.gnit.lucenekmp.store.FSDirectory

class ChurchSearchEngine(
    private val indexPath: Path,
    private val geonames: List<GeoName>,
    private val indexVersion: String = "development",
) {
    private val resolver = GeoNameResolver(geonames)
    private val json = Json { ignoreUnknownKeys = true }
    private val analyzer = JapaneseAnalyzer()

    fun church(churchId: String): ChurchDetailResponse? {
        val directory = FSDirectory.open(indexPath)
        return try {
            StandardDirectoryReader.open(directory, null, null).use { reader ->
                val searcher = IndexSearcher(reader)
                val scoreDoc = searcher.search(TermQuery(Term(ChurchIndex.FIELD_ID, churchId)), 1).scoreDocs.firstOrNull()
                    ?: return@use null
                val recordJson = requireNotNull(searcher.storedFields().document(scoreDoc.doc).get(ChurchIndex.FIELD_RECORD))
                val record = json.decodeFromString<ChurchRecord>(recordJson)
                ChurchDetailResponse(
                    indexVersion = indexVersion,
                    churchId = record.id,
                    name = record.name,
                    denominationId = record.denominationId,
                    category = record.category,
                    address = record.address,
                    location = record.location,
                    websiteUrl = record.websiteUrl,
                    socialProfiles = record.socialProfiles,
                )
            }
        } finally {
            directory.close()
        }
    }

    fun search(request: ChurchSearchRequest): ChurchSearchResponse {
        require(request.query.isNotBlank()) { "query must not be blank" }
        require(request.offset >= 0) { "offset must not be negative" }
        require(request.limit in 1..100) { "limit must be between 1 and 100" }
        require(request.radiusKm == null || request.radiusKm > 0.0) { "radiusKm must be positive" }

        var resolved = resolver.resolve(request.query, request.radiusKm)
        if (resolved.locations.isEmpty() && request.userLocation != null) {
            resolved = resolved.copy(
                locations = listOf(
                    ResolvedLocation(
                        matchedText = "",
                        code = "device",
                        name = "Current location",
                        type = GeoNameType.DEVICE,
                        center = request.userLocation,
                        radiusKm = request.radiusKm ?: DEFAULT_DEVICE_RADIUS_KM,
                    )
                )
            )
        }
        val directory = FSDirectory.open(indexPath)
        return try {
            StandardDirectoryReader.open(directory, null, null).use { reader ->
            val searcher = IndexSearcher(reader)
            val query = buildQuery(resolved)
            val topDocs = searcher.search(query, request.offset + request.limit)
            val storedFields = searcher.storedFields()
            val hits = topDocs.scoreDocs.drop(request.offset).map { scoreDoc ->
                val recordJson = requireNotNull(storedFields.document(scoreDoc.doc).get(ChurchIndex.FIELD_RECORD))
                val record = json.decodeFromString<ChurchRecord>(recordJson)
                val distance = resolved.locations.minOfOrNull {
                    GeoNameResolver.distanceKm(it.center, record.location)
                }
                ChurchSearchHit(
                    churchId = record.id,
                    name = record.name,
                    denominationId = record.denominationId,
                    category = record.category,
                    address = record.address,
                    location = record.location,
                    websiteUrl = record.websiteUrl,
                    score = scoreDoc.score,
                    distanceKm = distance,
                    matchedPages = matchingPages(record.pages, resolved.textQuery),
                    socialProfiles = record.socialProfiles,
                )
            }
            ChurchSearchResponse(
                indexVersion = indexVersion,
                query = request.query,
                textQuery = resolved.textQuery,
                resolvedLocations = resolved.locations,
                total = topDocs.totalHits.value,
                offset = request.offset,
                limit = request.limit,
                hits = hits,
            )
            }
        } finally {
            directory.close()
        }
    }

    private fun buildQuery(resolved: ResolvedGeoQuery): Query {
        val searchableText = removeGenericChurchWordsWhenQualified(resolved.textQuery)
        val textQuery = if (searchableText.isBlank()) {
            MatchAllDocsQuery()
        } else {
            MultiFieldQueryParser(
                arrayOf(
                    ChurchIndex.FIELD_NAME,
                    ChurchIndex.FIELD_CATEGORY,
                    ChurchIndex.FIELD_DENOMINATION,
                    ChurchIndex.FIELD_ADDRESS,
                    ChurchIndex.FIELD_CONTENT,
                    ChurchIndex.FIELD_SOCIAL,
                ),
                analyzer,
                mapOf(
                    ChurchIndex.FIELD_NAME to 8f,
                    ChurchIndex.FIELD_CATEGORY to 5f,
                    ChurchIndex.FIELD_DENOMINATION to 5f,
                    ChurchIndex.FIELD_ADDRESS to 3f,
                    ChurchIndex.FIELD_CONTENT to 1f,
                    ChurchIndex.FIELD_SOCIAL to 1f,
                ),
            ).apply { setDefaultOperator(QueryParser.Operator.AND) }.parse(escapeLuceneSyntax(searchableText))
                ?: MatchAllDocsQuery()
        }
        if (resolved.locations.isEmpty()) return textQuery

        val geoUnion = BooleanQuery.Builder().apply {
            resolved.locations.flatMap(::geoAreas).forEach { location ->
                add(
                    LatLonPoint.newDistanceQuery(
                        ChurchIndex.FIELD_LOCATION,
                        location.center.latitude,
                        location.center.longitude,
                        location.radiusKm * 1000.0,
                    ),
                    BooleanClause.Occur.SHOULD,
                )
            }
        }.build()
        return BooleanQuery.Builder().apply {
            add(textQuery, BooleanClause.Occur.MUST)
            add(geoUnion, BooleanClause.Occur.FILTER)
            resolved.locations.map { it.matchedText }.filter { it.isNotBlank() }.distinct().forEach { placeName ->
                QueryParser(ChurchIndex.FIELD_ADDRESS, analyzer).parse(escapeLuceneSyntax(placeName))?.let {
                    add(it, BooleanClause.Occur.SHOULD)
                }
            }
        }.build()
    }

    private fun removeGenericChurchWordsWhenQualified(query: String): String {
        val withoutGenericWords = query
            .replace(Regex("教会|チャペル"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return withoutGenericWords.takeIf { it.isNotBlank() } ?: query
    }

    private fun escapeLuceneSyntax(query: String): String = buildString(query.length) {
        query.forEach { character ->
            if (character in LUCENE_QUERY_SPECIAL_CHARACTERS) append('\\')
            append(character)
        }
    }

    private fun geoAreas(location: ResolvedLocation): List<ResolvedLocation> {
        if (location.type != GeoNameType.PREFECTURE) return listOf(location)
        val municipalities = geonames.filter {
            it.prefectureCode == location.code && it.type != GeoNameType.PREFECTURE
        }
        return municipalities.takeIf { it.isNotEmpty() }?.map {
            ResolvedLocation(it.name, it.code, it.name, it.type, it.center, it.coveringRadiusKm)
        } ?: listOf(location)
    }

    private fun matchingPages(pages: List<CrawledPage>, query: String): List<MatchedPage> {
        if (query.isBlank()) return pages.take(1).map { MatchedPage(it.url, it.title, snippet(it.text, "")) }
        val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        return pages.asSequence().filter { page ->
            val haystack = "${page.title}\n${page.text}".lowercase()
            terms.all { haystack.contains(it.lowercase()) }
        }.take(3).map { page -> MatchedPage(page.url, page.title, snippet(page.text, terms.firstOrNull().orEmpty())) }.toList()
    }

    private fun snippet(text: String, term: String, width: Int = 180): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= width) return compact
        val index = compact.lowercase().indexOf(term.lowercase()).coerceAtLeast(0)
        val start = (index - width / 3).coerceAtLeast(0).coerceAtMost(compact.length - width)
        return (if (start > 0) "…" else "") + compact.substring(start, start + width) +
            (if (start + width < compact.length) "…" else "")
    }

    companion object {
        const val DEFAULT_DEVICE_RADIUS_KM = 25.0
        private val LUCENE_QUERY_SPECIAL_CHARACTERS = setOf(
            '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/',
        )
    }
}

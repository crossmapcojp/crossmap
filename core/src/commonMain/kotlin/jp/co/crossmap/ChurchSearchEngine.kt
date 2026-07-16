package jp.co.crossmap

import kotlinx.serialization.json.Json
import okio.Path
import org.gnit.lucenekmp.analysis.tokenattributes.CharTermAttribute
import org.gnit.lucenekmp.document.LatLonPoint
import org.gnit.lucenekmp.index.StandardDirectoryReader
import org.gnit.lucenekmp.index.Term
import org.gnit.lucenekmp.queryparser.classic.MultiFieldQueryParser
import org.gnit.lucenekmp.queryparser.classic.QueryParser
import org.gnit.lucenekmp.search.BooleanClause
import org.gnit.lucenekmp.search.BooleanQuery
import org.gnit.lucenekmp.search.BoostQuery
import org.gnit.lucenekmp.search.IndexSearcher
import org.gnit.lucenekmp.search.MatchAllDocsQuery
import org.gnit.lucenekmp.search.Query
import org.gnit.lucenekmp.search.TermQuery
import org.gnit.lucenekmp.store.FSDirectory

class ChurchSearchEngine(
    private val indexPath: Path,
    private val geonames: List<GeoName>,
    private val indexVersion: String = "development",
    private val churchPageUrls: Map<String, String> = emptyMap(),
    private val languageCode: String = "ja",
) {
    private val resolver = GeoNameResolver(geonames)
    private val json = Json { ignoreUnknownKeys = true }
    private val normalizedLanguage = languageCode.substringBefore('-').lowercase()
    private val analyzer = ChurchIndex.analyzer(normalizedLanguage)

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
                    englishName = record.englishName,
                    localizedNames = record.localizedNames,
                    localizedDenominationNames = record.localizedDenominationNames,
                    titleLanguages = record.titleLanguages,
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
            val query = buildQuery(resolved, request.titleLanguages)
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
                    englishName = record.englishName,
                    localizedNames = record.localizedNames,
                    localizedDenominationNames = record.localizedDenominationNames,
                    titleLanguages = record.titleLanguages,
                    denominationId = record.denominationId,
                    category = record.category,
                    address = record.address,
                    location = record.location,
                    websiteUrl = record.websiteUrl,
                    score = scoreDoc.score,
                    distanceKm = distance,
                    matchedPages = matchingPages(record.pages, resolved.textQuery),
                    socialProfiles = record.socialProfiles,
                    detailUrl = churchPageUrls[record.id],
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

    private fun buildQuery(resolved: ResolvedGeoQuery, titleLanguages: List<String>): Query {
        val searchableText = removeGenericChurchWordsWhenQualified(resolved.textQuery)
        val textQuery = if (searchableText.isBlank()) {
            MatchAllDocsQuery()
        } else {
            val fields = linkedMapOf(
                ChurchIndex.FIELD_NAME to 8f,
                ChurchIndex.localizedNameField(normalizedLanguage) to 8f,
                ChurchIndex.FIELD_GEONAME to 6f,
                ChurchIndex.FIELD_DENOMINATION to 5f,
            )
            if (normalizedLanguage == "ja") {
                fields[ChurchIndex.FIELD_CATEGORY] = 5f
                fields[ChurchIndex.FIELD_ADDRESS] = 3f
                fields[ChurchIndex.FIELD_CONTENT] = 1f
                fields[ChurchIndex.FIELD_SOCIAL] = 1f
            }
            runCatching { MultiFieldQueryParser(
                fields.keys.toTypedArray(),
                analyzer,
                fields,
            ).apply { setDefaultOperator(QueryParser.Operator.AND) }.parse(escapeLuceneSyntax(searchableText)) }
                .getOrNull() ?: analyzedMultiFieldQuery(searchableText, fields)
        }
        if (resolved.locations.isEmpty()) return withTitleLanguageFilter(textQuery, titleLanguages)

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
        val geoQuery = BooleanQuery.Builder().apply {
            add(textQuery, BooleanClause.Occur.MUST)
            add(geoUnion, BooleanClause.Occur.FILTER)
            val addressLocations = resolved.locations.filter {
                normalizedLanguage == "ja" && it.type != GeoNameType.DEVICE
            }
            val addressUnion = BooleanQuery.Builder().apply {
                addressLocations.map { it.name }.filter { it.isNotBlank() }.distinct().forEach { placeName ->
                    QueryParser(ChurchIndex.FIELD_ADDRESS, analyzer).parse(escapeLuceneSyntax(placeName))?.let {
                        add(it, BooleanClause.Occur.SHOULD)
                    }
                }
            }.build()
            if (addressLocations.isNotEmpty()) add(addressUnion, BooleanClause.Occur.FILTER)
            val nameFields = arrayOf(ChurchIndex.FIELD_NAME, ChurchIndex.localizedNameField(normalizedLanguage))
            addressLocations.map { it.matchedText }.filter { it.isNotBlank() }.distinct().forEach { placeName ->
                MultiFieldQueryParser(nameFields, analyzer).parse(escapeLuceneSyntax(placeName))?.let {
                    add(BoostQuery(it, 6f), BooleanClause.Occur.SHOULD)
                }
            }
        }.build()
        return withTitleLanguageFilter(geoQuery, titleLanguages)
    }

    private fun withTitleLanguageFilter(query: Query, titleLanguages: List<String>): Query {
        val normalized = titleLanguages.map { it.substringBefore('-').lowercase() }.filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return query
        val languages = BooleanQuery.Builder().apply {
            normalized.forEach { language ->
                add(TermQuery(Term(ChurchIndex.FIELD_TITLE_LANGUAGE, language)), BooleanClause.Occur.SHOULD)
            }
        }.build()
        return BooleanQuery.Builder().apply {
            add(query, BooleanClause.Occur.MUST)
            add(languages, BooleanClause.Occur.FILTER)
        }.build()
    }

    private fun analyzedMultiFieldQuery(text: String, fields: Map<String, Float>): Query {
        return BooleanQuery.Builder().apply {
            fields.forEach { (field, boost) ->
                val terms = buildList {
                    analyzer.tokenStream(field, text).use { stream ->
                        val term = stream.addAttribute(CharTermAttribute::class)
                        stream.reset()
                        while (stream.incrementToken()) add(term.toString())
                        stream.end()
                    }
                }.distinct()
                if (terms.isNotEmpty()) {
                    val withinField = BooleanQuery.Builder().apply {
                        terms.forEach { token ->
                            add(TermQuery(Term(field, token)), BooleanClause.Occur.MUST)
                        }
                    }.build()
                    add(BoostQuery(withinField, boost), BooleanClause.Occur.SHOULD)
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
        if (normalizedLanguage != "ja") return emptyList()
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

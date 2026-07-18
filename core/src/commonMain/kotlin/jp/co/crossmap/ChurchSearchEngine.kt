package jp.co.crossmap

import io.github.oshai.kotlinlogging.KotlinLogging
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

private val logger = KotlinLogging.logger {}

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

        logger.info { "search: query='${request.query}', lang=$languageCode, offset=${request.offset}, limit=${request.limit}, radiusKm=${request.radiusKm}, userLocation=${request.userLocation}, titleLanguages=${request.titleLanguages}" }

        val resolved = resolveRequest(request)
        val geoAreaLocations = resolved.locations.flatMap(::geoAreas)
        logger.info { renderQueryPlan(request, resolved, geoAreaLocations) }
        val directory = FSDirectory.open(indexPath)
        return try {
            StandardDirectoryReader.open(directory, null, null).use { reader ->
            val searcher = IndexSearcher(reader)
            val queries = buildQueries(request.query, resolved, geoAreaLocations, request.titleLanguages)
            val requestedHits = maxOf(1, request.offset + request.limit)
            val mergedQuery = BooleanQuery.Builder().apply {
                add(BoostQuery(queries[0], EXACT_NAME_STAGE_BOOST), BooleanClause.Occur.SHOULD)
                add(BoostQuery(queries[1], ALL_NAME_TOKENS_STAGE_BOOST), BooleanClause.Occur.SHOULD)
                add(queries[2], BooleanClause.Occur.SHOULD)
                setMinimumNumberShouldMatch(1)
            }.build()
            logger.trace {
                "search-query-lucene:\n" +
                    "  tier.1=${queries[0]}\n" +
                    "  tier.2=${queries[1]}\n" +
                    "  tier.3=${queries[2]}\n" +
                    "  merged=$mergedQuery"
            }
            val topDocs = searcher.search(mergedQuery, requestedHits)
            val orderedScoreDocs = topDocs.scoreDocs.toList()
            val totalHits = topDocs.totalHits.value
            val storedFields = searcher.storedFields()
            val hits = orderedScoreDocs.drop(request.offset).take(request.limit).map { scoreDoc ->
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
                    matchedPages = matchingPages(record.pages, request.query),
                    socialProfiles = record.socialProfiles,
                    detailUrl = churchPageUrls[record.id],
                )
            }
            val response = ChurchSearchResponse(
                indexVersion = indexVersion,
                query = request.query,
                textQuery = request.query.trim(),
                resolvedLocations = resolved.locations,
                total = totalHits,
                offset = request.offset,
                limit = request.limit,
                hits = hits,
            )
            logger.info { "search: total=$totalHits, returned=${hits.size}" }
            hits.forEach { hit ->
                logger.trace { "search: hit id=${hit.churchId}, name='${hit.name}', english='${hit.englishName}', score=${hit.score}, distance=${hit.distanceKm}km, pages=${hit.matchedPages.size}" }
            }
            response
            }
        } finally {
            directory.close()
        }
    }

    internal fun explainQuery(request: ChurchSearchRequest): String {
        val resolved = resolveRequest(request)
        return renderQueryPlan(request, resolved, resolved.locations.flatMap(::geoAreas))
    }

    private fun resolveRequest(request: ChurchSearchRequest): ResolvedGeoQuery {
        var resolved = resolver.resolve(request.query, request.radiusKm, normalizedLanguage)
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
            logger.trace {
                "search: no geoname matched, using device location ${request.userLocation} " +
                    "with radius=${resolved.locations.first().radiusKm}km"
            }
        }
        return resolved
    }

    private fun buildQueries(
        fullQuery: String,
        resolved: ResolvedGeoQuery,
        geoAreaLocations: List<ResolvedLocation>,
        titleLanguages: List<String>,
    ): List<Query> {
        val exact = buildExactNameQuery(fullQuery, titleLanguages)
        val allNameTokens = buildAllNameTokensQuery(fullQuery, titleLanguages)
        val general = buildGeneralQuery(fullQuery, resolved, geoAreaLocations, titleLanguages)
        return listOf(
            exact,
            allNameTokens,
            general,
        )
    }

    private fun buildExactNameQuery(fullQuery: String, titleLanguages: List<String>): Query = withTitleLanguageFilter(
        TermQuery(Term(ChurchIndex.FIELD_NAME_EXACT, ChurchIndex.normalizeExactName(fullQuery))),
        titleLanguages,
    )

    private fun buildAllNameTokensQuery(fullQuery: String, titleLanguages: List<String>): Query {
        val fields = nameSearchFields()
        val tokens = analyzedTokens(fullQuery)
        val genericTokens = GENERIC_CHURCH_WORDS[normalizedLanguage].orEmpty()
            .flatMap(::analyzedTokens)
            .toSet()
        val query = if (tokens.any { it !in genericTokens }) {
            analyzedAcrossFieldsQuery(tokens, fields)
        } else {
            BooleanQuery.Builder().build()
        }
        return withTitleLanguageFilter(query, titleLanguages)
    }

    private fun buildGeneralQuery(
        fullQuery: String,
        resolved: ResolvedGeoQuery,
        geoAreaLocations: List<ResolvedLocation>,
        titleLanguages: List<String>,
    ): Query {
        val searchableText = fullQuery.trim()
        val textQuery = if (searchableText.isBlank()) {
            logger.debug { "buildQuery: text query is blank after filtering, using MatchAllDocsQuery" }
            MatchAllDocsQuery()
        } else {
            val fields = generalSearchFields()
            logger.debug { "buildQuery: fields=[${fields.entries.joinToString { "${it.key}:boost=${it.value}" }}], analyzer=$normalizedLanguage" }
            analyzedAcrossFieldsQuery(searchableText, fields)
        }
        if (resolved.locations.isEmpty()) {
            logger.debug { "buildQuery: no locations resolved, text-only query" }
            return withTitleLanguageFilter(textQuery, titleLanguages)
        }

        logger.debug { "buildQuery: building geo-filtered query for ${resolved.locations.size} location(s)" }
        val geoUnion = BooleanQuery.Builder().apply {
            geoAreaLocations.forEach { location ->
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

    private fun nameSearchFields(): LinkedHashMap<String, Float> = linkedMapOf(
        ChurchIndex.FIELD_NAME to 8f,
        ChurchIndex.localizedNameField(normalizedLanguage) to 8f,
    )

    private fun generalSearchFields(): LinkedHashMap<String, Float> = linkedMapOf(
        ChurchIndex.FIELD_NAME to 8f,
        ChurchIndex.localizedNameField(normalizedLanguage) to 8f,
        ChurchIndex.FIELD_GEONAME to 6f,
        ChurchIndex.FIELD_DENOMINATION to 5f,
    ).apply {
        if (normalizedLanguage == "ja") {
            this[ChurchIndex.FIELD_CATEGORY] = 5f
            this[ChurchIndex.FIELD_ADDRESS] = 3f
            this[ChurchIndex.FIELD_CONTENT] = 1f
            this[ChurchIndex.FIELD_SOCIAL] = 1f
        }
    }

    private fun renderQueryPlan(
        request: ChurchSearchRequest,
        resolved: ResolvedGeoQuery,
        geoAreaLocations: List<ResolvedLocation>,
    ): String {
        val tokens = analyzedTokens(request.query)
        val genericTokens = GENERIC_CHURCH_WORDS[normalizedLanguage].orEmpty()
            .flatMap(::analyzedTokens)
            .toSet()
        val allTokenTierEnabled = tokens.any { it !in genericTokens }
        val locations = resolved.locations.joinToString(prefix = "[", postfix = "]") { location ->
            val matched = location.matchedText.takeIf(String::isNotBlank) ?: "device"
            "$matched -> ${location.name}(${location.type}, code=${location.code}, " +
                "center=${location.center.latitude},${location.center.longitude}, radiusKm=${location.radiusKm})"
        }
        val geoAreaSample = geoAreaLocations.take(8).joinToString { it.name }
        val omittedGeoAreas = (geoAreaLocations.size - 8).coerceAtLeast(0)
        val geoAreas = when {
            geoAreaLocations.isEmpty() -> "none"
            omittedGeoAreas == 0 -> "$geoAreaSample (${geoAreaLocations.size} area(s))"
            else -> "$geoAreaSample, ... +$omittedGeoAreas (${geoAreaLocations.size} area(s))"
        }
        val titleFilter = request.titleLanguages
            .map { it.substringBefore('-').lowercase() }
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { listOf("none") }
        return buildString {
            appendLine("search-query-plan:")
            appendLine("  input.original=${request.query.replace(Regex("""\s+"""), " ").trim()}")
            appendLine("  input.language=$normalizedLanguage analyzer=${analyzerDisplayName()}")
            appendLine("  input.pagination=offset:${request.offset},limit:${request.limit} titleLanguageFilter=$titleFilter")
            appendLine("  analysis.tokens=$tokens operator=AND")
            appendLine("  analysis.geonameRemainder=${resolved.textQuery.ifBlank { "<empty>" }}")
            appendLine("  analysis.locations=$locations")
            appendLine("  tier.1.type=EXACT_NAME boost=$EXACT_NAME_STAGE_BOOST field=${ChurchIndex.FIELD_NAME_EXACT} " +
                "term=${ChurchIndex.normalizeExactName(request.query)} geoFilter=false")
            appendLine("  tier.2.type=ALL_NAME_TOKENS boost=$ALL_NAME_TOKENS_STAGE_BOOST enabled=$allTokenTierEnabled " +
                "tokens=$tokens fields=${formatFields(nameSearchFields())} geoFilter=false" +
                if (allTokenTierEnabled) "" else " reason=generic-only-query")
            appendLine("  tier.3.type=FULL_QUERY_GEO boost=normal tokens=$tokens fields=${formatFields(generalSearchFields())}")
            appendLine("  tier.3.geoFilter=${if (resolved.locations.isEmpty()) "false" else "true"} areas=$geoAreas")
            appendLine("  tier.3.japaneseAddressGuard=${normalizedLanguage == "ja" && resolved.locations.any { it.type != GeoNameType.DEVICE }}")
            append("  merge=SHOULD(tier.1,tier.2,tier.3) minimumShouldMatch=1 deduplicate=true")
        }
    }

    private fun formatFields(fields: Map<String, Float>): String = fields.entries.joinToString(
        prefix = "[",
        postfix = "]",
    ) { (field, boost) -> "$field^$boost" }

    private fun analyzerDisplayName(): String = when (normalizedLanguage) {
        "ja" -> "JapaneseAnalyzer"
        "en" -> "EnglishAnalyzer"
        "ko" -> "KoreanAnalyzer"
        "pt" -> "PortugueseAnalyzer"
        "id" -> "IndonesianAnalyzer"
        else -> "StandardAnalyzer"
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

    private fun analyzedAcrossFieldsQuery(text: String, fields: Map<String, Float>): Query {
        return analyzedAcrossFieldsQuery(analyzedTokens(text), fields)
    }

    private fun analyzedTokens(text: String): List<String> = buildList {
            analyzer.tokenStream(ChurchIndex.FIELD_NAME, text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class)
                stream.reset()
                while (stream.incrementToken()) add(term.toString())
                stream.end()
            }
        }.distinct()

    private fun analyzedAcrossFieldsQuery(tokens: List<String>, fields: Map<String, Float>): Query {
        return BooleanQuery.Builder().apply {
            tokens.forEach { token ->
                val tokenAcrossFields = BooleanQuery.Builder().apply {
                    fields.forEach { (field, boost) ->
                        add(
                            BoostQuery(TermQuery(Term(field, token)), boost),
                            BooleanClause.Occur.SHOULD,
                        )
                    }
                }.build()
                add(tokenAcrossFields, BooleanClause.Occur.MUST)
            }
        }.build()
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
            it.prefectureCode == location.code &&
                it.type != GeoNameType.PREFECTURE &&
                it.includeInPrefectureSearch
        }
        val areas = municipalities.takeIf { it.isNotEmpty() }?.map {
            ResolvedLocation(it.name, it.code, it.name, it.type, it.center, it.coveringRadiusKm)
        } ?: listOf(location)
        logger.debug { "geoAreas: prefecture '${location.name}'(${location.code}) expanded to ${areas.size} area(s): ${areas.joinToString { it.name }}" }
        return areas
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
        private const val EXACT_NAME_STAGE_BOOST = 1_000_000f
        private const val ALL_NAME_TOKENS_STAGE_BOOST = 1_000f
        private val LUCENE_QUERY_SPECIAL_CHARACTERS = setOf(
            '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/',
        )
        private val GENERIC_CHURCH_WORDS = mapOf(
            "ja" to listOf("教会", "チャペル"),
            "en" to listOf("church", "churches", "chapel"),
            "ko" to listOf("교회", "채플"),
            "pt" to listOf("igreja", "igrejas", "capela"),
            "id" to listOf("gereja", "kapel"),
        )
    }
}

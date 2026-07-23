package jp.co.crossmap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import okio.Path
import org.gnit.lucenekmp.analysis.tokenattributes.CharTermAttribute
import org.gnit.lucenekmp.document.LatLonDocValuesField
import org.gnit.lucenekmp.document.LatLonPoint
import org.gnit.lucenekmp.index.StandardDirectoryReader
import org.gnit.lucenekmp.index.Term
import org.gnit.lucenekmp.queryparser.classic.MultiFieldQueryParser
import org.gnit.lucenekmp.search.BooleanClause
import org.gnit.lucenekmp.search.BooleanQuery
import org.gnit.lucenekmp.search.BoostQuery
import org.gnit.lucenekmp.search.IndexSearcher
import org.gnit.lucenekmp.search.MatchAllDocsQuery
import org.gnit.lucenekmp.search.Query
import org.gnit.lucenekmp.search.Sort
import org.gnit.lucenekmp.search.TermQuery
import org.gnit.lucenekmp.store.FSDirectory
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

internal fun renderSearchTiming(timings: Map<String, Duration>, total: Duration): String {
    val totalNanoseconds = total.inWholeNanoseconds.coerceAtLeast(1L)
    fun percentage(duration: Duration): Double =
        ((duration.inWholeNanoseconds.toDouble() / totalNanoseconds * 1_000.0).roundToInt() / 10.0)
    val measured = timings.values.fold(Duration.ZERO) { accumulated, duration -> accumulated + duration }
    val other = (total - measured).coerceAtLeast(Duration.ZERO)
    return buildString {
        appendLine("search-timing:")
        timings.forEach { (name, duration) ->
            appendLine("  $name=$duration (${percentage(duration)}%)")
        }
        appendLine("  other=$other (${percentage(other)}%)")
        append("  total=$total (100.0%)")
    }
}

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
    private val directoryHolder = lazy { FSDirectory.open(indexPath) }
    private val readerHolder = lazy { StandardDirectoryReader.open(directoryHolder.value, null, null) }
    private val searcherHolder = lazy { IndexSearcher(readerHolder.value) }

    fun church(churchId: String): ChurchDetailResponse? {
        val searcher = searcherHolder.value
        val scoreDoc = searcher.search(TermQuery(Term(ChurchIndex.FIELD_ID, churchId)), 1).scoreDocs.firstOrNull()
            ?: return null
        val recordJson = requireNotNull(searcher.storedFields().document(scoreDoc.doc).get(ChurchIndex.FIELD_RECORD))
        val record = json.decodeFromString<ChurchRecord>(recordJson)
        return ChurchDetailResponse(
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

    /** Opens and touches the immutable snapshot so the first user request does not pay index startup cost. */
    fun warmUp() {
        searcherHolder.value.search(MatchAllDocsQuery(), 1)
    }

    fun close() {
        if (readerHolder.isInitialized()) readerHolder.value.close()
        if (directoryHolder.isInitialized()) directoryHolder.value.close()
    }

    fun search(request: ChurchSearchRequest): ChurchSearchResponse {
        val timeSource = TimeSource.Monotonic
        val totalMark = timeSource.markNow()
        val timings = linkedMapOf<String, Duration>()
        fun <T> measured(name: String, block: () -> T): T {
            val mark = timeSource.markNow()
            return try {
                block()
            } finally {
                timings[name] = mark.elapsedNow()
            }
        }

        measured("request.validate") {
            require(request.query.isNotBlank()) { "query must not be blank" }
            require(request.offset >= 0) { "offset must not be negative" }
            require(request.limit in 1..100) { "limit must be between 1 and 100" }
            require(request.radiusKm == null || request.radiusKm > 0.0) { "radiusKm must be positive" }
        }
        measured("logging.request") {
            logger.info { "search: query='${request.query}', lang=$languageCode, offset=${request.offset}, limit=${request.limit}, radiusKm=${request.radiusKm}, userLocation=${request.userLocation}, titleLanguages=${request.titleLanguages}" }
        }

        val resolved = measured("geoname.resolve") { resolveRequest(request) }
        val analysis = measured("query.analyze") {
            QueryAnalysis(
                fullTokens = analyzedTokens(request.query),
                remainderTokens = analyzedTokens(resolved.textQuery),
            )
        }
        measured("logging.queryPlan") { logger.info { renderQueryPlan(request, resolved, analysis) } }
        val searcher = measured("index.acquireSearcher") { searcherHolder.value }
        val response = run {
                val queries = measured("query.buildTiers") {
                    buildQueries(request.query, resolved, request.titleLanguages, analysis)
                }
                val requestedHits = maxOf(1, request.offset + request.limit)
                val mergedQuery = measured("query.mergeTiers") {
                    BooleanQuery.Builder().apply {
                        add(BoostQuery(queries[0], EXACT_NAME_STAGE_BOOST), BooleanClause.Occur.SHOULD)
                        add(BoostQuery(queries[1], ALL_NAME_TOKENS_STAGE_BOOST), BooleanClause.Occur.SHOULD)
                        add(queries[2], BooleanClause.Occur.SHOULD)
                        setMinimumNumberShouldMatch(1)
                    }.build()
                }
                measured("logging.luceneQuery") {
                    logger.trace {
                        "search-query-lucene:\n" +
                            "  tier.1=${queries[0]}\n" +
                            "  tier.2=${queries[1]}\n" +
                            "  tier.3=${queries[2]}\n" +
                            "  tier.4=${queries[3]}\n" +
                            "  merged=$mergedQuery"
                    }
                }
                val deviceLocation = resolved.locations.singleOrNull()?.takeIf { it.type == GeoNameType.DEVICE }
                val deviceDistanceSort = deviceLocation?.let {
                    Sort(LatLonDocValuesField.newDistanceSort(ChurchIndex.FIELD_LOCATION, it.center.latitude, it.center.longitude))
                }
                fun collect(query: Query) = if (deviceDistanceSort == null) {
                    searcher.search(query, requestedHits)
                } else {
                    searcher.search(query, requestedHits, deviceDistanceSort, true)
                }
                val fastDocs = measured("lucene.collect.fastTiers") { collect(mergedQuery) }
                val fastTotalHits = fastDocs.totalHits.value
                val finalDocs = if (fastTotalHits < requestedHits.toLong()) {
                    val withContentFallback = measured("query.addContentFallback") {
                        BooleanQuery.Builder().apply {
                            add(mergedQuery, BooleanClause.Occur.SHOULD)
                            add(queries[3], BooleanClause.Occur.SHOULD)
                            setMinimumNumberShouldMatch(1)
                        }.build()
                    }
                    measured("lucene.collect.withContentFallback") { collect(withContentFallback) }
                } else {
                    fastDocs
                }
                val totalHits = finalDocs.totalHits.value
                val storedFields = searcher.storedFields()
                val hits = measured("results.decode") {
                    finalDocs.scoreDocs.drop(request.offset).take(request.limit).map { scoreDoc ->
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
                }
                measured("response.build") {
                    ChurchSearchResponse(
                        indexVersion = indexVersion,
                        query = request.query,
                        textQuery = request.query.trim(),
                        resolvedLocations = resolved.locations,
                        total = totalHits,
                        offset = request.offset,
                        limit = request.limit,
                        hits = hits,
                    )
                }
        }
        measured("logging.results") {
            logger.info { "search: total=${response.total}, returned=${response.hits.size}" }
            response.hits.forEach { hit ->
                logger.trace { "search: hit id=${hit.churchId}, name='${hit.name}', english='${hit.englishName}', score=${hit.score}, distance=${hit.distanceKm}km, pages=${hit.matchedPages.size}" }
            }
        }
        val total = totalMark.elapsedNow()
        logger.info { renderSearchTiming(timings, total) }
        return response
    }

    internal fun explainQuery(request: ChurchSearchRequest): String {
        val resolved = resolveRequest(request)
        return renderQueryPlan(
            request,
            resolved,
            QueryAnalysis(
                fullTokens = analyzedTokens(request.query),
                remainderTokens = analyzedTokens(resolved.textQuery),
            ),
        )
    }

    private fun resolveRequest(request: ChurchSearchRequest): ResolvedGeoQuery {
        var resolved = resolver.resolve(
            request.query,
            request.radiusKm,
            normalizedLanguage,
            request.userLocation,
        )
        if (resolved.locations.isEmpty() && request.userLocation != null) {
            val nearbyArea = resolver.nearestAdministrativeArea(request.userLocation)
            resolved = resolved.copy(
                locations = listOf(
                    ResolvedLocation(
                        matchedText = "",
                        code = "device",
                        name = nearbyArea?.let { resolver.localizedName(it, normalizedLanguage) } ?: "Current location",
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
        titleLanguages: List<String>,
        analysis: QueryAnalysis,
    ): List<Query> {
        val exact = buildExactNameQuery(fullQuery, resolved, titleLanguages)
        val allNameTokens = buildAllNameTokensQuery(analysis, resolved, titleLanguages)
        val general = buildGeneralQuery(
            fullQuery,
            resolved,
            titleLanguages,
            analysis,
            addGeonameNameBoost = false,
        )
        val contentFallback = buildGeneralQuery(
            fullQuery,
            resolved,
            titleLanguages,
            analysis,
            fields = contentFallbackFields(),
            matchAllForGeonameOnly = false,
            addGeonameNameBoost = false,
        )
        return listOf(
            exact,
            allNameTokens,
            general,
            contentFallback,
        )
    }

    private fun buildExactNameQuery(
        fullQuery: String,
        resolved: ResolvedGeoQuery,
        titleLanguages: List<String>,
    ): Query {
        val term = ChurchIndex.normalizeExactName(fullQuery)
        val exact = BooleanQuery.Builder().apply {
            add(
                BoostQuery(TermQuery(Term(ChurchIndex.FIELD_NAME_EXACT, term)), EXACT_NAME_FIELD_BOOST),
                BooleanClause.Occur.SHOULD,
            )
            if (normalizedLanguage == "ja") {
                add(
                    BoostQuery(TermQuery(Term(ChurchIndex.FIELD_NAME_READING_EXACT, term)), EXACT_NAME_READING_BOOST),
                    BooleanClause.Occur.SHOULD,
                )
                add(
                    BoostQuery(
                        TermQuery(Term(ChurchIndex.FIELD_DENOMINATION_READING_EXACT, term)),
                        EXACT_DENOMINATION_READING_BOOST,
                    ),
                    BooleanClause.Occur.SHOULD,
                )
                add(
                    BoostQuery(TermQuery(Term(ChurchIndex.FIELD_CATEGORY_READING_EXACT, term)), EXACT_CATEGORY_READING_BOOST),
                    BooleanClause.Occur.SHOULD,
                )
            }
            setMinimumNumberShouldMatch(1)
        }.build()
        return withAuthoritativeGeonameFilter(withTitleLanguageFilter(exact, titleLanguages), resolved)
    }

    private fun buildAllNameTokensQuery(
        analysis: QueryAnalysis,
        resolved: ResolvedGeoQuery,
        titleLanguages: List<String>,
    ): Query {
        val fields = nameSearchFields()
        val query = if (allNameTokenTierEnabled(analysis, resolved)) {
            analyzedAcrossFieldsQuery(analysis.fullTokens, fields)
        } else {
            BooleanQuery.Builder().build()
        }
        return withAuthoritativeGeonameFilter(withTitleLanguageFilter(query, titleLanguages), resolved)
    }

    private fun buildGeneralQuery(
        fullQuery: String,
        resolved: ResolvedGeoQuery,
        titleLanguages: List<String>,
        analysis: QueryAnalysis,
        fields: LinkedHashMap<String, Float> = generalSearchFields(),
        matchAllForGeonameOnly: Boolean = true,
        addGeonameNameBoost: Boolean = true,
    ): Query {
        val namedGeonameOnly = resolved.locations.singleOrNull()?.type != GeoNameType.DEVICE &&
            resolved.locations.isNotEmpty() && resolved.textQuery.isBlank()
        val namedAddressEntity = resolved.locations.singleOrNull()?.type != GeoNameType.DEVICE && resolved.locations.isNotEmpty()
        val searchableText = when {
            namedGeonameOnly && matchAllForGeonameOnly -> ""
            namedAddressEntity && resolved.textQuery.isNotBlank() -> resolved.textQuery
            else -> fullQuery.trim()
        }
        val searchableTokens = when {
            searchableText.isBlank() -> emptyList()
            namedAddressEntity && resolved.textQuery.isNotBlank() -> analysis.remainderTokens
            else -> analysis.fullTokens
        }.filterNot { it in genericChurchTokens }
        val textQuery = if (searchableTokens.isEmpty()) {
            logger.debug { "buildQuery: text query is blank after filtering, using MatchAllDocsQuery" }
            MatchAllDocsQuery()
        } else {
            logger.debug { "buildQuery: fields=[${fields.entries.joinToString { "${it.key}:boost=${it.value}" }}], analyzer=$normalizedLanguage" }
            analyzedAcrossFieldsQuery(searchableTokens, fields)
        }
        if (resolved.locations.isEmpty()) {
            logger.debug { "buildQuery: no locations resolved, text-only query" }
            return withTitleLanguageFilter(textQuery, titleLanguages)
        }

        val location = resolved.locations.single()
        logger.debug { "buildQuery: building one ${location.type} filter for '${location.name}'(${location.code})" }
        val locationFilter = if (location.type == GeoNameType.DEVICE) {
            LatLonPoint.newDistanceQuery(
                ChurchIndex.FIELD_LOCATION,
                location.center.latitude,
                location.center.longitude,
                location.radiusKm * 1000.0,
            )
        } else {
            TermQuery(Term(ChurchIndex.FIELD_ADDRESS_GEONAME_CODE, location.code))
        }
        val geoQuery = BooleanQuery.Builder().apply {
            add(textQuery, BooleanClause.Occur.MUST)
            add(locationFilter, BooleanClause.Occur.FILTER)
            val nameFields = arrayOf(ChurchIndex.FIELD_NAME, ChurchIndex.localizedNameField(normalizedLanguage))
            resolved.locations.filter { addGeonameNameBoost && it.type != GeoNameType.DEVICE }
                .map { it.matchedText }.filter { it.isNotBlank() }.distinct().forEach { placeName ->
                MultiFieldQueryParser(nameFields, analyzer).parse(escapeLuceneSyntax(placeName))?.let {
                    add(BoostQuery(it, 6f), BooleanClause.Occur.SHOULD)
                }
            }
        }.build()
        return withTitleLanguageFilter(geoQuery, titleLanguages)
    }

    private fun nameSearchFields(): LinkedHashMap<String, Float> = linkedMapOf(
        ChurchIndex.FIELD_NAME to 8f,
        ChurchIndex.FIELD_NAME_READING to 7f,
        ChurchIndex.FIELD_MINISTER to 6f,
    )

    private fun generalSearchFields(): LinkedHashMap<String, Float> = linkedMapOf(
        ChurchIndex.FIELD_SEARCH_COMPACT to 1f,
    )

    private fun contentFallbackFields(): LinkedHashMap<String, Float> = if (normalizedLanguage == "ja") {
        linkedMapOf(
            ChurchIndex.FIELD_CONTENT to 1f,
            ChurchIndex.FIELD_SOCIAL to 1f,
        )
    } else {
        linkedMapOf()
    }

    private fun renderQueryPlan(
        request: ChurchSearchRequest,
        resolved: ResolvedGeoQuery,
        analysis: QueryAnalysis,
    ): String {
        val tokens = analysis.fullTokens
        val allTokenTierEnabled = allNameTokenTierEnabled(analysis, resolved)
        val locations = resolved.locations.joinToString(prefix = "[", postfix = "]") { location ->
            val matched = location.matchedText.takeIf(String::isNotBlank) ?: "device"
            val spatialDetails = if (location.type == GeoNameType.DEVICE) {
                "center=${location.center.latitude},${location.center.longitude}, radiusKm=${location.radiusKm}"
            } else {
                "representativeCenter=${location.center.latitude},${location.center.longitude}"
            }
            "$matched -> ${location.name}(${location.type}, code=${location.code}, $spatialDetails)"
        }
        val candidates = resolved.candidates.joinToString(prefix = "[", postfix = "]") {
            "${it.name}(${it.type}, code=${it.code})"
        }
        val filter = resolved.locations.singleOrNull()?.let {
            if (it.type == GeoNameType.DEVICE) {
                "DEVICE_LAT_LON_DISTANCE field=${ChurchIndex.FIELD_LOCATION} center=${it.center.latitude},${it.center.longitude} radiusKm=${it.radiusKm}"
            } else {
                "NAMED_ADDRESS_CODE field=${ChurchIndex.FIELD_ADDRESS_GEONAME_CODE} code=${it.code} radiusFilter=false"
            }
        } ?: "none"
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
            appendLine("  analysis.geonameCandidates=$candidates")
            appendLine("  analysis.geonameSelection=${resolved.selectionReason}")
            appendLine("  analysis.explicitAdministrativeName=${resolved.explicitAdministrativeName}")
            appendLine("  analysis.locations=$locations")
            val topTierGeoFilter = resolved.explicitAdministrativeName || resolved.textQuery.isBlank() ||
                resolved.locations.singleOrNull()?.type == GeoNameType.DEVICE
            appendLine(
                "  tier.1.type=EXACT_NAME_OR_READING boost=$EXACT_NAME_STAGE_BOOST " +
                    "fields=[${ChurchIndex.FIELD_NAME_EXACT}^$EXACT_NAME_FIELD_BOOST, " +
                    "${ChurchIndex.FIELD_NAME_READING_EXACT}^$EXACT_NAME_READING_BOOST, " +
                    "${ChurchIndex.FIELD_DENOMINATION_READING_EXACT}^$EXACT_DENOMINATION_READING_BOOST, " +
                    "${ChurchIndex.FIELD_CATEGORY_READING_EXACT}^$EXACT_CATEGORY_READING_BOOST] " +
                    "term=${ChurchIndex.normalizeExactName(request.query)} geoFilter=$topTierGeoFilter",
            )
            appendLine("  tier.2.type=ALL_NAME_TOKENS boost=$ALL_NAME_TOKENS_STAGE_BOOST enabled=$allTokenTierEnabled " +
                "tokens=$tokens fields=${formatFields(nameSearchFields())} geoFilter=$topTierGeoFilter" +
                if (allTokenTierEnabled) "" else " reason=generic-or-geoname-only-query")
            val namedLocation = resolved.locations.isNotEmpty() && resolved.locations.none { it.type == GeoNameType.DEVICE }
            val tier3Tokens = when {
                namedLocation && resolved.textQuery.isBlank() -> emptyList()
                namedLocation -> analysis.remainderTokens
                else -> analysis.fullTokens
            }.filterNot { it in genericChurchTokens }
            appendLine("  tier.3.type=ADDRESS_ENTITY_REMAINDER boost=normal tokens=$tier3Tokens fields=${formatFields(generalSearchFields())}")
            val tier3TextMode = when {
                resolved.locations.isNotEmpty() && resolved.locations.none { it.type == GeoNameType.DEVICE } && tier3Tokens.isEmpty() -> "MATCH_ALL_GEONAME_ONLY_OR_GENERIC"
                resolved.locations.isNotEmpty() && resolved.locations.none { it.type == GeoNameType.DEVICE } -> "GEONAME_REMAINDER"
                else -> "FULL_QUERY"
            }
            appendLine("  tier.3.textMode=$tier3TextMode")
            appendLine("  tier.3.geoFilter=${if (resolved.locations.isEmpty()) "false" else "true"} filter=$filter")
            appendLine("  tier.4.type=CONTENT_FALLBACK fields=${formatFields(contentFallbackFields())} executeWhen=fastTierTotal<requestedHits")
            append("  merge=SHOULD(tier.1,tier.2,tier.3) minimumShouldMatch=1 deduplicate=true")
        }
    }

    private fun formatFields(fields: Map<String, Float>): String = fields.entries.joinToString(
        prefix = "[",
        postfix = "]",
    ) { (field, boost) -> "$field^$boost" }

    private fun allNameTokenTierEnabled(analysis: QueryAnalysis, resolved: ResolvedGeoQuery): Boolean {
        if (analysis.fullTokens.none { it !in genericChurchTokens }) return false
        if (resolved.locations.isEmpty()) return true
        return analysis.remainderTokens.any { it !in genericChurchTokens }
    }

    private val genericChurchTokens: Set<String> by lazy {
        GENERIC_CHURCH_WORDS[normalizedLanguage].orEmpty().flatMap(::analyzedTokens).toSet()
    }

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

    private fun withAuthoritativeGeonameFilter(query: Query, resolved: ResolvedGeoQuery): Query {
        val location = resolved.locations.singleOrNull()
        if (location?.type == GeoNameType.DEVICE) {
            return BooleanQuery.Builder().apply {
                add(query, BooleanClause.Occur.MUST)
                add(
                    LatLonPoint.newDistanceQuery(
                        ChurchIndex.FIELD_LOCATION,
                        location.center.latitude,
                        location.center.longitude,
                        location.radiusKm * 1_000.0,
                    ),
                    BooleanClause.Occur.FILTER,
                )
            }.build()
        }
        if (location == null || (!resolved.explicitAdministrativeName && resolved.textQuery.isNotBlank())) return query
        return BooleanQuery.Builder().apply {
            add(query, BooleanClause.Occur.MUST)
            add(
                TermQuery(Term(ChurchIndex.FIELD_ADDRESS_GEONAME_CODE, location.code)),
                BooleanClause.Occur.FILTER,
            )
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
        const val DEFAULT_DEVICE_RADIUS_KM = 50.0
        private const val EXACT_NAME_STAGE_BOOST = 1_000_000f
        private const val ALL_NAME_TOKENS_STAGE_BOOST = 1_000f
        private const val EXACT_NAME_FIELD_BOOST = 100f
        private const val EXACT_NAME_READING_BOOST = 50f
        private const val EXACT_DENOMINATION_READING_BOOST = 10f
        private const val EXACT_CATEGORY_READING_BOOST = 5f
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

    private data class QueryAnalysis(
        val fullTokens: List<String>,
        val remainderTokens: List<String>,
    )
}

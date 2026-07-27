package jp.co.crossmap.crawl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.double
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoName as SearchGeoName
import jp.co.crossmap.crawl.denomination.OfficialDenominationChurchListPipeline
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private class Crawl : CliktCommand(name = "crossmap-crawl") {
    override fun run() = Unit
}

private class ReadGoogleSavedPlaces : CrawlCommand("read-google-saved-places", CrawlReport.READ_GOOGLE_SAVED_PLACES) {
    private val input by option(
        "--input",
        help = "Google Takeout/Saved directory; defaults to crossmap.googleSavedPlaces in local.properties",
    )
    private val resources by option("--resources").default("resources")

    override fun execute(audit: CrawlCommandAudit) {
        val inputDirectory = GoogleSavedPlacesInput.resolve(input) ?: throw UsageError(
            "Google Saved Places input is not configured. Pass --input, set ${GoogleSavedPlacesInput.PROPERTY} " +
                "in local.properties, or set ${GoogleSavedPlacesInput.ENVIRONMENT}.",
        )
        val paths = CrossmapPaths(Path.of(resources))
        val rawDirectory = paths.googleSavedPlaces
        audit.input("saved_places_directory", inputDirectory)
        audit.input("resources", Path.of(resources).toAbsolutePath().normalize())
        audit.setting("included_lists", GoogleSavedPlacesCrawler.GMAP_DEFAULT_LISTS.sorted().joinToString(","))
        val excludedUrls = GoogleSavedPlacesCrawler.readExcludedUrls(
            listOf(
                rawDirectory.resolve("exclusions/church-exclude.csv"),
                rawDirectory.resolve("exclusions/catholic-exclude.csv"),
            ),
        )
        val report = GoogleSavedPlacesCrawler().readDirectory(
            inputDirectory = inputDirectory,
            output = rawDirectory.resolve("seeds.json"),
            includedLists = GoogleSavedPlacesCrawler.GMAP_DEFAULT_LISTS,
            excludedUrls = excludedUrls,
        )
        Files.writeString(
            rawDirectory.resolve("seed-read-report.json"),
            Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report),
        )
        audit.metric("files_read", report.filesRead)
        audit.metric("rows_read", report.rowsRead)
        audit.metric("seeds_written", report.seedsWritten)
        audit.metric("duplicates_merged", report.duplicatesMerged)
        report.namePatternCounts.forEach { (pattern, count) ->
            audit.metric("name_pattern.${pattern.lowercase()}", count)
        }
        report.languageCounts.forEach { (language, count) ->
            audit.metric("language.$language", count)
        }
        audit.metric("errors", report.errors.size)
        report.errors.take(50).forEachIndexed { index, error ->
            audit.detail("error_${index + 1}", "${error.sourceFile}:${error.rowNumber}|${error.title.orEmpty()}|${error.message}")
        }
        audit.output("seeds", rawDirectory.resolve("seeds.json").toAbsolutePath().normalize())
        audit.output("report", rawDirectory.resolve("seed-read-report.json").toAbsolutePath().normalize())
        echo(
            "Read ${report.rowsRead} rows from ${report.filesRead} lists: " +
                "${report.seedsWritten} unique Google places, ${report.duplicatesMerged} duplicates, ${report.errors.size} errors",
        )
    }
}

private class ResolveGoogleSavedPlaces : CrawlCommand("resolve-google-saved-places", CrawlReport.RESOLVE_GOOGLE_SAVED_PLACES) {
    private val resources by option("--resources").default("resources")
    private val concurrency by option("--concurrency").int().default(6)
    private val offline by option("--offline", help = "Require copied Google CID cache; never send requests").flag()

    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        val dictionaries = ChurchNameEnglishDictionary.load(root)
        val denominations = json.decodeFromString<List<Denomination>>(Files.readString(paths.denominationCatalog))
        val nameGeonames = loadChurchNameGeonames(paths) + dictionaries.geonames
        val localizer = MultilingualChurchNameLocalizer(
            dictionaries = dictionaries,
            congregationTerms = CongregationTermDictionary.load(root),
            denominations = denominations,
            denominationNames = DenominationNameCatalogFiles.load(root),
            geonames = nameGeonames,
            multilingualGeonames = mergeReviewedChurchGeoNames(
                createGeoName(paths).readMultilingualLexicon(paths.geoNameMultilingualLexicon),
                loadReviewedChurchGeoNames(paths),
            ),
            branchGeonames = loadChurchNameGeoAliases(paths) + dictionaries.geonames.keys,
        )
        audit.input("seeds", paths.googleSavedPlaces.resolve("seeds.json").toAbsolutePath().normalize())
        audit.setting("concurrency", concurrency)
        audit.setting("offline", offline)
        val report = GoogleMapsPlaceResolver(
            pageSource = CachedGoogleMapsPageSource(paths.googleMapsPages, allowNetwork = !offline),
            parser = GoogleMapsPlaceParser(localizer, ExcludedChurchListingDomains.policy(root)),
            maxConcurrency = concurrency,
        ).resolve(root, paths.cacheRoot)
        audit.metric("seeds", report.seeds)
        audit.metric("candidates", report.candidates)
        audit.metric("cache_hits", report.cacheHits)
        audit.metric("fetched", report.fetched)
        audit.metric("catholic_non_churches_filtered", report.catholicNonChurchesFiltered)
        report.namePatternCounts.forEach { (pattern, count) ->
            audit.metric("name_pattern.${pattern.lowercase()}", count)
        }
        report.languageCounts.forEach { (language, count) ->
            audit.metric("language.$language", count)
        }
        report.localizedNameCounts.forEach { (language, count) ->
            audit.metric("localized_name.$language", count)
        }
        report.nameComponentRoleCounts.forEach { (role, count) ->
            audit.metric("name_component_role.${role.lowercase()}", count)
        }
        audit.metric("candidates_with_unresolved_name_components", report.candidatesWithUnresolvedNameComponents)
        audit.metric("errors", report.errors.size)
        report.errors.take(50).forEachIndexed { index, error ->
            audit.detail("error_${index + 1}", "${error.id}|${error.name}|${error.message}")
        }
        audit.output("candidates", paths.googleSavedPlaces.resolve("google-place-candidates.json").toAbsolutePath().normalize())
        audit.output("enriched_seeds", paths.googleSavedPlaces.resolve("seeds.json").toAbsolutePath().normalize())
        audit.output("report", paths.googleSavedPlaces.resolve("google-place-resolution-report.json").toAbsolutePath().normalize())
        echo(
            "Resolved ${report.candidates}/${report.seeds} Google places: ${report.cacheHits} cache hits, " +
                "${report.fetched} fetched, ${report.catholicNonChurchesFiltered} Catholic non-churches filtered, " +
                "${report.errors.size} errors",
        )
    }
}

private class PromoteGoogleSavedPlaces : CrawlCommand("promote-google-saved-places", CrawlReport.PROMOTE_GOOGLE_SAVED_PLACES) {
    private val resources by option("--resources").default("resources")
    private val englishModel by option("--english-model").default(CAT_TRANSLATE_MODEL)
    private val denominationModel by option("--denomination-model").default("qwen3:1.7b")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val threshold by option("--confidence-threshold").double().default(0.80)
    private val limit by option("--limit", help = "Maximum unresolved denominations to send to Ollama").int().default(100)
    private val programmaticOnly by option("--programmatic-only", help = "Disable every Ollama fallback").flag()
    private val websiteCacheHours by option(
        "--website-cache-hours",
        help = "Reuse website crawl results younger than this many hours; 0 forces revalidation",
    ).int().default(30 * 24)
    private val skipWebsiteRefresh by option("--skip-website-refresh").flag()
    private val skipDirectoryCrawl by option("--skip-directory-crawl").flag()
    private val skipDenominationCleanup by option(
        "--skip-denomination-cleanup",
        help = "Preserve existing/candidate denomination evidence without rerunning denomination matching",
    ).flag()
    private val dryRun by option("--dry-run", help = "Complete the pending catalog but do not replace churches.json").flag()

    override fun execute(audit: CrawlCommandAudit) = runBlocking {
        val startedNanos = System.nanoTime()
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        val socialInputs = SocialExportInputs.load()
        audit.input("candidates", paths.googleSavedPlaces.resolve("google-place-candidates.json").toAbsolutePath().normalize())
        audit.input("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        socialInputs.youtubeSubscribedChannelsCsv?.let { audit.input("youtube_subscriptions", it) }
        socialInputs.instagramFollowingJson?.let { audit.input("instagram_following", it) }
        socialInputs.facebookFollowingRawHtml?.let { audit.input("facebook_following_html", it) }
        socialInputs.facebookFollowingJson?.let { audit.input("facebook_following_json", it) }
        socialInputs.twitterListMembersJson?.let { audit.input("x_list_members", it) }
        audit.setting("english_model", if (programmaticOnly) "disabled" else englishModel)
        audit.setting("denomination_model", if (programmaticOnly) "disabled" else denominationModel)
        audit.setting("confidence_threshold", threshold)
        audit.setting("llm_limit", limit)
        audit.setting("programmatic_only", programmaticOnly)
        audit.setting("refresh_websites", !skipWebsiteRefresh)
        audit.setting("website_cache_hours", websiteCacheHours)
        audit.setting("crawl_directories", !skipDirectoryCrawl)
        audit.setting("cleanup_denominations", !skipDenominationCleanup)
        audit.setting("dry_run", dryRun)
        require(limit > 0) { "limit must be positive" }
        require(websiteCacheHours >= 0) { "website-cache-hours must not be negative" }
        if (!programmaticOnly) checkOllamaDiskSpace()
        val denominations = json.decodeFromString<List<Denomination>>(
            Files.readString(root.resolve("catalog/denominations.json")),
        )
        val dictionaries = ChurchNameEnglishDictionary.load(root)
        val nameGeonames = loadChurchNameGeonames(paths) + dictionaries.geonames
        val detectionOnlyGeonames = loadChurchNameGeoAliases(paths)
        val denominationMatcher = if (programmaticOnly) {
            EntityMatcher { EntityMatchDecision(null, 0.0, reasoning = "Ollama disabled") }
        } else {
            KoogOllamaEntityMatcher(denominationModel, ollamaUrl)
        }
        var englishCache: CachingChurchEnglishNameTranslator? = null
        var componentCache: CachingChurchNameComponentTranslator? = null
        var componentPipeline: ComponentCompletingChurchEnglishNameTranslator? = null
        val translator = if (programmaticOnly) {
            ChurchEnglishNameTranslator { church ->
                error("No deterministic English name for ${church.id} (${church.name})")
            }
        } else {
            val cachedComponents = CachingChurchNameComponentTranslator(
                delegate = FallbackChurchNameComponentTranslator(
                    primary = KoogChurchNameComponentTranslator(englishModel, ollamaUrl),
                    fallback = KoogChurchNameComponentTranslator("qwen3:1.7b", ollamaUrl),
                ),
                model = "$englishModel|typed-components-v5",
                cacheFile = paths.churchNameTranslation.resolve("components.json"),
                batchSize = 32,
            ).also { componentCache = it }
            val componentDelegate = ComponentCompletingChurchEnglishNameTranslator(
                analyzer = ChurchNameComponentAnalyzer(
                    denominations,
                    nameGeonames,
                    concepts = dictionaries.concepts,
                    detectionOnlyGeonames = detectionOnlyGeonames,
                    dictionaryEntries = dictionaries.entries,
                ),
                componentTranslator = cachedComponents,
                fullNameFallback = KoogChurchEnglishNameTranslator(englishModel, ollamaUrl),
                modelName = englishModel,
            ).also { componentPipeline = it }
            CachingChurchEnglishNameTranslator(
                delegate = componentDelegate,
                model = "$englishModel|component-pipeline-v6",
                cacheFile = paths.churchNameTranslation.resolve("whole-names.json"),
                onBatchCompleted = { stats ->
                    echo("English-name LLM progress: ${stats.translated} translated, ${stats.hits} cached, ${stats.batches} batches")
                },
            ).also { englishCache = it }
        }
        val englishResolver = ChurchEnglishNameResolver(
            ChurchNameEnglishTranslationRules.create(denominations, nameGeonames, dictionaries.concepts),
            translator,
        )
        val report = GoogleSavedPlacesCleanupWorkflow(
            postCrawlCleanup = PostCrawlCleanup(
                matcher = denominationMatcher,
                confidenceThreshold = threshold,
                webpageGuesser = if (programmaticOnly) null else {
                    KoogDenominationGuesser(denominations, denominationModel, ollamaUrl)
                },
                englishNameResolver = englishResolver,
            ),
            englishNameResolver = englishResolver,
            churchWebsiteCrawler = ChurchWebsiteCrawler(cacheFreshness = Duration.ofHours(websiteCacheHours.toLong())),
        ).run(
            resourcesRoot = root,
            llmLimit = limit,
            enableLlm = !programmaticOnly,
            refreshWebsites = !skipWebsiteRefresh,
            crawlDirectories = !skipDirectoryCrawl,
            cleanupDenominations = !skipDenominationCleanup,
            socialInputs = socialInputs,
            promote = !dryRun,
            cacheRoot = paths.cacheRoot,
        )
        audit.metric("raw_candidates", report.rawCandidates)
        audit.metric("exact_duplicates_merged", report.exactDuplicatesMerged)
        audit.metric("non_google_records_retained", report.nonGoogleRecordsRetained)
        audit.metric("existing_evidence_reused", report.existingEvidenceReused)
        audit.metric("website_fetched", report.websiteFetched)
        audit.metric("website_errors", report.websiteErrors)
        audit.metric("directory_candidates", report.directoryCandidates)
        audit.metric("denomination_programmatic", report.denominationProgrammatic)
        audit.metric("denomination_llm", report.denominationLlm)
        audit.metric("denomination_human", report.denominationHuman)
        audit.metric("denomination_uncertain", report.denominationUncertain)
        audit.metric("social_accounts_parsed", report.socialAccountsParsed)
        audit.metric("social_website_urls_migrated", report.socialWebsiteUrlsMigrated)
        audit.metric("social_exact_matches", report.socialExactMatches)
        audit.metric("social_estimated_matches", report.socialEstimatedMatches)
        audit.metric("social_not_matched", report.socialNotMatched)
        audit.metric("social_excluded", report.socialExcluded)
        audit.metric("english_names_programmatic", report.englishNamesProgrammatic)
        audit.metric("english_names_llm", report.englishNamesLlm)
        audit.metric("final_churches", report.finalChurches)
        audit.metric("promoted", report.promoted)
        report.stageDurationsSeconds.forEach { (stage, seconds) ->
            audit.metric("duration_${stage}_seconds", "%.3f".format(java.util.Locale.ROOT, seconds))
        }
        audit.output("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.output("pending_catalog", paths.cleanup.resolve("google-saved-places-pending.json").toAbsolutePath().normalize())
        writeDataCleanupStat(
            total = report.finalChurches,
            deterministic = report.englishNamesProgrammatic,
            llm = report.englishNamesLlm,
            startedNanos = startedNanos,
            model = if (programmaticOnly) "disabled" else englishModel,
            errors = report.websiteErrors + (englishCache?.stats?.errors ?: 0),
            timeouts = englishCache?.stats?.timeouts ?: 0,
            status = if (dryRun) "dry-run" else "success",
        )
        echo(
            "Google Saved Places cleanup: ${report.rawCandidates} candidates -> ${report.finalChurches} churches; " +
                "English ${report.englishNamesProgrammatic} deterministic + ${report.englishNamesLlm} LLM; " +
                "denominations ${report.denominationProgrammatic} deterministic + ${report.denominationLlm} LLM + " +
                "${report.denominationHuman} human; promoted=${report.promoted}",
        )
    }
}

private fun checkOllamaDiskSpace() {
    val exit = ProcessBuilder("df", "-h", "/media/joel/llms").inheritIO().start().waitFor()
    check(exit == 0) { "df failed; refusing to invoke Ollama" }
}

private class BuildGeonames : CrawlCommand("build-geonames", CrawlReport.BUILD_GEONAMES) {
    private val resources by option("--resources").default("resources")
    private val citiesSource by option("--cities-source").required()
    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val catalog = root.resolve("catalog/churches.json")
        val output = root.resolve("geonames/japan.json")
        audit.input("catalog", catalog.toAbsolutePath().normalize())
        audit.input("cities_source", Path.of(citiesSource).toAbsolutePath().normalize())
        val churches = json.decodeFromString<List<ChurchRecord>>(java.nio.file.Files.readString(root.resolve("catalog/churches.json")))
        val paths = CrossmapPaths(root)
        val geoName = jp.co.crossmap.crawl.GeoName()
        val multilingualLexicon = geoName.readMultilingualLexicon(paths.geoNamesMultilingualLexicon)
            .ifEmpty { geoName.readMultilingualLexicon(paths.geoNameMultilingualLexicon) }
        val result = GeoCatalogBuilder().build(churches, Path.of(citiesSource), output, multilingualLexicon)
        audit.metric("churches", churches.size)
        audit.metric("geonames_generated", result.size)
        audit.output("geonames", output.toAbsolutePath().normalize())
        echo("Generated ${result.size} geonames")
    }
}

private class PrepareGeoNameCache : CrawlCommand("prepare-geoname-cache", CrawlReport.PREPARE_GEONAME_CACHE) {
    private val resources by option("--resources").default("resources")
    private val input by option("--input", help = "Official GeoNames JP.txt; defaults to cache/geoname/japan/JP.txt")

    override fun execute(audit: CrawlCommandAudit) {
        val paths = CrossmapPaths(Path.of(resources))
        val geoName = createGeoName(paths)
        val japanText = input?.let(Path::of) ?: paths.geoNameOfficialJapan
        audit.input("jp_text", japanText.toAbsolutePath().normalize())
        val downloaded = geoName.ensureOfficialJapanDump(japanText)
        val alternateNamesText = paths.geoNameOfficialJapanAlternateNames
        val alternateNamesDownloaded = geoName.ensureOfficialJapanAlternateNamesDump(alternateNamesText)
        val jmaCityDownloaded = geoName.ensureJmaCityDictionary(paths.jmaCityDictionary)
        val jmaLexicon = geoName.readJmaMultilingualLexicon(paths.jmaCityDictionary)
        val report = geoName.buildJapanCache(
            japanText = japanText,
            japanCsv = paths.geoNameJapanCsv,
            lexiconJson = paths.geoNameEnglishLexicon,
        )
        val alternateReport = geoName.buildJapanAlternateNamesCache(
            japanText = japanText,
            alternateNamesText = alternateNamesText,
            multilingualLexiconJson = paths.geoNamesMultilingualLexicon,
        )
        val geoNamesLexicon = geoName.readMultilingualLexicon(paths.geoNamesMultilingualLexicon)
        val mergedLexicon = geoName.mergeMultilingualLexicons(geoNamesLexicon, jmaLexicon)
        geoName.writeMultilingualLexicon(paths.geoNameMultilingualLexicon, mergedLexicon)
        audit.metric("downloaded", downloaded)
        audit.metric("source_rows_read", report.sourceRowsRead)
        audit.metric("japan_rows_retained", report.japanRowsRetained)
        audit.metric("japanese_aliases", report.japaneseAliases)
        audit.metric("ambiguous_aliases_resolved", report.ambiguousAliasesResolved)
        audit.metric("geoname_aliases_before_cleanup", report.cleanup.inputNames)
        audit.metric("geoname_aliases_removed", report.cleanup.removedNames)
        audit.metric("reviewed_church_name_aliases_removed", report.cleanup.reviewedChurchNamesRemoved)
        audit.metric("katakana_only_aliases_removed", report.cleanup.katakanaOnlyNamesRemoved)
        audit.metric("address_block_aliases_removed", report.cleanup.addressBlocksRemoved)
        audit.metric("alternate_names_downloaded", alternateNamesDownloaded)
        audit.metric("alternate_rows_read", alternateReport.alternateRowsRead)
        audit.metric("matched_alternate_rows", alternateReport.matchedAlternateRows)
        alternateReport.translatedAliases.forEach { (language, count) -> audit.metric("alternate_$language", count) }
        audit.metric("jma_city_downloaded", jmaCityDownloaded)
        audit.metric("jma_japanese_aliases", jmaLexicon.size)
        ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES.forEach { language ->
            audit.metric("jma_$language", jmaLexicon.values.count { language in it })
            audit.metric(
                "jma_added_$language",
                jmaLexicon.count { (japanese, names) -> language in names && language !in geoNamesLexicon[japanese].orEmpty() },
            )
        }
        audit.output("japan_csv", paths.geoNameJapanCsv.toAbsolutePath().normalize())
        audit.output("english_lexicon", paths.geoNameEnglishLexicon.toAbsolutePath().normalize())
        audit.output("jma_city_dictionary", paths.jmaCityDictionary.toAbsolutePath().normalize())
        audit.output("multilingual_lexicon", paths.geoNameMultilingualLexicon.toAbsolutePath().normalize())
        echo(
            "GeoNames cache: ${if (downloaded) "downloaded JP.zip; " else "reused JP.txt; "}" +
                "read ${report.sourceRowsRead} JP.txt rows, retained ${report.japanRowsRetained} JP rows, " +
                "built ${report.japaneseAliases} Japanese/English aliases (${report.ambiguousAliasesResolved} ambiguities ranked)",
        )
    }
}

private class BuildChurchGeonames : CrawlCommand("church-geonames", CrawlReport.CHURCH_GEONAMES) {
    private val resources by option("--resources").default("resources")

    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        val candidates = paths.googleSavedPlaces.resolve("google-place-candidates.json")
        audit.input("google_place_candidates", candidates.toAbsolutePath().normalize())
        val cleaner = JapaneseGeoNameCleaner.fromCsv(paths.geoNameDuplicatedChurchNames)
        val geoName = GeoName(cleaner = cleaner)
        geoName.ensureJmaCityDictionary(paths.jmaCityDictionary)
        val geoNamesLexicon = geoName.readMultilingualLexicon(paths.geoNamesMultilingualLexicon)
            .ifEmpty { geoName.readMultilingualLexicon(paths.geoNameMultilingualLexicon) }
        val jmaLexicon = geoName.readJmaMultilingualLexicon(paths.jmaCityDictionary)
        val mergedLexicon = geoName.mergeMultilingualLexicons(geoNamesLexicon, jmaLexicon)
        val beforeJma = buildTemporaryChurchGeoNameReport(candidates, root, geoNamesLexicon, cleaner)
        val report = ChurchGeoNameTranslationCatalog(cleaner = cleaner).build(
            candidates,
            root,
            mergedLexicon,
        )
        val coverageLog = writeGeoNameTranslationCoverageLog(beforeJma, report, jmaLexicon.size)
        audit.metric("church_geonames", report.churchGeoNames)
        audit.metric("geonames_before_cleanup", report.geonamesBeforeCleanup)
        audit.metric("geonames_removed", report.geonamesBeforeCleanup - report.churchGeoNames)
        audit.metric("reviewed_church_names_removed", report.reviewedChurchNamesRemoved)
        audit.metric("katakana_only_names_removed", report.katakanaOnlyNamesRemoved)
        audit.metric("address_blocks_removed", report.addressBlocksRemoved)
        audit.metric("title_geonames", report.titleGeoNames)
        audit.metric("address_geonames", report.addressGeoNames)
        ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES.forEach { language ->
            audit.metric("translated_$language", report.translatedCounts.getValue(language))
            audit.metric("missing_$language", report.missingCounts.getValue(language))
            audit.metric("title_missing_$language", report.titleMissingCounts.getValue(language))
            audit.metric("address_missing_$language", report.addressMissingCounts.getValue(language))
            audit.output(
                "title_missing_$language",
                root.resolve("geonames/church-ja-$language-title-missing.csv").toAbsolutePath().normalize(),
            )
            audit.output(
                "address_missing_$language",
                root.resolve("geonames/church-ja-$language-address-missing.csv").toAbsolutePath().normalize(),
            )
        }
        audit.output("church_geonames", root.resolve("geonames/church-ja-all.json").toAbsolutePath().normalize())
        audit.output("church_geoname_usage", root.resolve("geonames/church-usage.json").toAbsolutePath().normalize())
        audit.output("translation_coverage_log", coverageLog.toAbsolutePath().normalize())
        echo(
            "Collected ${report.churchGeoNames} title/address geonames; " +
                ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES.joinToString { language ->
                    "$language=${report.translatedCounts.getValue(language)} translated/${report.missingCounts.getValue(language)} missing"
                }
        )
    }
}

private fun buildTemporaryChurchGeoNameReport(
    candidates: Path,
    resourcesRoot: Path,
    translations: Map<String, Map<String, String>>,
    cleaner: JapaneseGeoNameCleaner,
): ChurchGeoNameTranslationReport {
    val temporaryResources = Files.createTempDirectory("crossmap-geoname-coverage-before-jma")
    return try {
        val sourceDirectory = resourcesRoot.resolve("geonames")
        val temporaryDirectory = Files.createDirectories(temporaryResources.resolve("geonames"))
        ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES.forEach { language ->
            listOf(
                "church-ja-$language-missing.csv",
                "church-ja-$language-title-missing.csv",
                "church-ja-$language-address-missing.csv",
            ).forEach { fileName ->
                val source = sourceDirectory.resolve(fileName)
                if (Files.isRegularFile(source)) Files.copy(source, temporaryDirectory.resolve(fileName))
            }
        }
        ChurchGeoNameTranslationCatalog(cleaner = cleaner).build(candidates, temporaryResources, translations)
    } finally {
        temporaryResources.toFile().deleteRecursively()
    }
}

private fun writeGeoNameTranslationCoverageLog(
    before: ChurchGeoNameTranslationReport,
    after: ChurchGeoNameTranslationReport,
    jmaAliases: Int,
): Path = CrawlReportLogging.log(
    CrawlReport.GEONAME_TRANSLATION_COVERAGE,
    buildString {
        appendLine("---")
        appendLine("source.jma_city_aliases=$jmaAliases")
        appendLine("before.church_geonames=${before.churchGeoNames}")
        appendLine("after.church_geonames=${after.churchGeoNames}")
        appendLine("before.title_geonames=${before.titleGeoNames}")
        appendLine("after.title_geonames=${after.titleGeoNames}")
        appendLine("before.address_geonames=${before.addressGeoNames}")
        appendLine("after.address_geonames=${after.addressGeoNames}")
        appendLine("cleanup.geonames_before=${after.geonamesBeforeCleanup}")
        appendLine("cleanup.geonames_after=${after.churchGeoNames}")
        appendLine("cleanup.reviewed_church_names_removed=${after.reviewedChurchNamesRemoved}")
        appendLine("cleanup.katakana_only_names_removed=${after.katakanaOnlyNamesRemoved}")
        appendLine("cleanup.address_blocks_removed=${after.addressBlocksRemoved}")
        ChurchGeoNameTranslationCatalog.TARGET_LANGUAGES.forEach { language ->
            val beforeMissing = before.missingCounts.getValue(language)
            val afterMissing = after.missingCounts.getValue(language)
            appendLine("language.$language.translated_before=${before.translatedCounts.getValue(language)}")
            appendLine("language.$language.translated_after=${after.translatedCounts.getValue(language)}")
            appendLine("language.$language.missing_before=$beforeMissing")
            appendLine("language.$language.missing_after=$afterMissing")
            appendLine("language.$language.title_missing_after=${after.titleMissingCounts.getValue(language)}")
            appendLine("language.$language.address_missing_after=${after.addressMissingCounts.getValue(language)}")
            appendLine("language.$language.missing_reduced_by_jma=${beforeMissing - afterMissing}")
        }
    },
)

private class BuildSnapshot : CrawlCommand("build-snapshot", CrawlReport.BUILD_SNAPSHOT) {
    private val resources by option("--resources").default("resources")
    private val version by option("--version").default(Instant.now().toString().replace(":", "-").substringBefore('.'))
    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        audit.input("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.input("geonames", paths.geonames.toAbsolutePath().normalize())
        audit.setting("version", version)
        val manifest = SnapshotBuilder().build(root, version, paths.cacheRoot)
        audit.metric("schema_version", manifest.schemaVersion)
        audit.metric("document_count", manifest.documentCount)
        audit.metric("archive_size", manifest.archiveSize)
        audit.metric("source_sha256", manifest.sourceSha256)
        audit.metric("archive_sha256", manifest.sha256)
        audit.output("index_directory", paths.searchIndexes.resolve(version).toAbsolutePath().normalize())
        audit.output("archive", manifest.archiveFile.orEmpty())
        echo("Built ${manifest.documentCount}-church snapshot ${manifest.indexVersion} (${manifest.sha256})")
    }
}

private class NormalizeAddresses : CrawlCommand("normalize-addresses", CrawlReport.ADDRESS_NORMALIZATION) {
    private val resources by option("--resources").default("resources")
    private val normalizerDirectory by option(
        "--normalizer-dir",
        help = "Local clone of Geolonia normalize-japanese-addresses",
    )
    private val concurrency by option("--concurrency").int().default(4)

    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        val localNormalizer = GeoloniaNormalizerInput.resolve(normalizerDirectory) ?: throw UsageError(
            "Geolonia normalizer is not configured. Set ${GeoloniaNormalizerInput.PROPERTY} in local.properties " +
                "or pass --normalizer-dir.",
        )
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(paths.churchCatalog))
        val geonames = json.decodeFromString<List<SearchGeoName>>(Files.readString(paths.geonames))
        val runner = projectLogsDirectory().parent.resolve("crawl/scripts/geolonia-normalize.mjs")
        audit.input("church_catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.input("geonames", paths.geonames.toAbsolutePath().normalize())
        audit.input("local_geolonia", localNormalizer)
        audit.setting("concurrency", concurrency)
        val report = JapaneseAddressNormalizationPipeline(
            LocalGeoloniaAddressNormalizer(localNormalizer, runner, concurrency),
        ).normalize(churches, geonames, paths.normalizedChurchAddresses)
        audit.metric("churches", report.entries.size)
        audit.metric("cache_reused", report.reused)
        audit.metric("cache_reenriched_for_geonames", report.reEnriched)
        audit.metric("success", report.entries.size - report.errors.size)
        audit.metric("failed", report.errors.size)
        listOf(0, 1, 2, 3, 8).forEach { level ->
            audit.metric("level.$level.${addressNormalizationLevelName(level)}", report.levelCounts[level] ?: 0)
        }
        report.entries.forEachIndexed { index, entry ->
            audit.detail(
                "church_${index + 1}",
                listOf(
                    "id=${entry.churchId}",
                    "name=${entry.churchName}",
                    "status=${entry.status}",
                    "level=${entry.level}:${entry.levelName}",
                    "original=${entry.originalAddress}",
                    "normalized=${entry.normalizedAddress.normalized}",
                    "prefecture=${entry.normalizedAddress.prefecture.orEmpty()}",
                    "municipality=${entry.normalizedAddress.municipality.orEmpty()}",
                    "ward=${entry.normalizedAddress.cityWard.orEmpty()}",
                    "locality=${entry.normalizedAddress.locality.orEmpty()}",
                    "number=${entry.normalizedAddress.addressNumber.orEmpty()}",
                    "building=${entry.normalizedAddress.building.orEmpty()}",
                    "error=${entry.error.orEmpty()}",
                ).joinToString("|"),
            )
        }
        report.errors.forEachIndexed { index, entry ->
            audit.detail(
                "error_${index + 1}",
                "id=${entry.churchId}|name=${entry.churchName}|address=${entry.originalAddress}|" +
                    "level=${entry.level}:${entry.levelName}|error=${entry.error.orEmpty()}",
            )
        }
        audit.output("normalized_addresses", paths.normalizedChurchAddresses.toAbsolutePath().normalize())
        echo(
            "Normalized ${report.entries.size - report.errors.size}/${report.entries.size} church addresses; " +
                "levels=${report.levelCounts}, errors=${report.errors.size}",
        )
    }
}

private class CrawlDenominationDirectories : CrawlCommand("crawl-denomination-directories", CrawlReport.CRAWL_DENOMINATION_DIRECTORIES) {
    private val resources by option("--resources").default("resources")
    private val forceRefresh by option("--force-refresh", help = "Invalidate dedicated official-list HTML caches and fetch them now").flag()
    private val dedicatedOnly by option("--dedicated-only", help = "Run only the authoritative UCCJ/JBC/JBBF/JACC crawlers").flag()
    private val denominationIds by option("--denomination", help = "Run one dedicated denomination crawler by id; repeat for multiple ids").multiple()
    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        audit.input("sources", root.resolve("sources/denominations.json").toAbsolutePath().normalize())
        audit.setting("force_refresh", forceRefresh)
        audit.setting("dedicated_only", dedicatedOnly)
        audit.setting("denominations", denominationIds.joinToString(",").ifBlank { "all" })
        val report = OfficialDenominationChurchListPipeline().run(
            root,
            paths.cacheRoot,
            forceRefresh = forceRefresh,
            crawlGenericDirectories = !dedicatedOnly && denominationIds.isEmpty(),
            denominationIds = denominationIds.mapTo(linkedSetOf()) { it.uppercase() }.takeIf { it.isNotEmpty() },
        )
        audit.metric("sources", report.sources)
        audit.metric("pages", report.pages)
        audit.metric("candidates", report.candidates)
        audit.metric("errors", report.errors)
        audit.metric("excluded_urls", report.excludedUrls)
        report.churchesByDenomination.toSortedMap().forEach { (id, count) ->
            audit.metric("${id.lowercase()}_churches", count)
        }
        audit.metric("official_cache_hits", report.cacheHits)
        report.reconciliation?.let { reconciliation ->
            audit.metric("official_matches", reconciliation.matchedOfficialEntries)
            audit.metric("denominations_assigned", reconciliation.assigned)
            audit.metric("unsupported_labels_removed", reconciliation.removedUnsupportedLabels)
            audit.metric("human_overrides_preserved", reconciliation.humanOverridesPreserved)
            audit.metric("unmatched_official_entries", reconciliation.unmatchedOfficialEntries)
            audit.block(reconciliation.toHumanReadableAuditLog())
        }
        audit.output("candidates", paths.cleanup.resolve("denomination-candidates.json").toAbsolutePath().normalize())
        audit.output("uccj_churches", root.resolve("crawl/uccj-churches.json").toAbsolutePath().normalize())
        audit.output("jbc_churches", root.resolve("crawl/jbc-churches.json").toAbsolutePath().normalize())
        audit.output("jbbf_churches", root.resolve("crawl/jbbf-churches.json").toAbsolutePath().normalize())
        audit.output("jacc_churches", root.resolve("crawl/jacc-churches.json").toAbsolutePath().normalize())
        audit.output("jhc_churches", root.resolve("crawl/jhc-churches.json").toAbsolutePath().normalize())
        audit.output("rcj_churches", root.resolve("crawl/rcj-churches.json").toAbsolutePath().normalize())
        audit.output("igm_churches", root.resolve("crawl/igm-churches.json").toAbsolutePath().normalize())
        audit.output("jag_churches", root.resolve("crawl/jag-churches.json").toAbsolutePath().normalize())
        audit.output("catholic_jp_churches", root.resolve("crawl/catholic_jp-churches.json").toAbsolutePath().normalize())
        audit.output("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        echo(
            "Crawled ${report.sources} denomination sources / ${report.pages} pages: ${report.candidates} candidates, " +
                report.churchesByDenomination.entries.joinToString { "${it.key}=${it.value}" } + ", ${report.errors} errors; " +
                "removed=${report.reconciliation?.removedUnsupportedLabels ?: 0} unsupported labels",
        )
    }
}

private class Refresh : CrawlCommand("refresh", CrawlReport.REFRESH) {
    private val resources by option("--resources").default("resources")
    private val concurrency by option("--max-concurrency").int().default(6)
    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        audit.input("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.setting("max_concurrency", concurrency)
        val report = ChurchWebsiteCrawler(concurrency).crawl(root, cacheRoot = paths.cacheRoot)
        audit.metric("churches", report.churches)
        audit.metric("fetched", report.fetched)
        audit.metric("unchanged", report.unchanged)
        audit.metric("errors", report.errors)
        audit.output("manifest", root.resolve("crawl/manifest.json").toAbsolutePath().normalize())
        audit.output("page_cache", paths.churchWebPages.toAbsolutePath().normalize())
        echo("Refreshed ${report.churches} churches: ${report.fetched} fetched, ${report.unchanged} unchanged, ${report.errors} errors")
    }
}

private class CleanupLlm : CrawlCommand("cleanup-llm", CrawlReport.CLEANUP_LLM) {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default("qwen3:4b")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val threshold by option("--confidence-threshold").double().default(0.80)
    private val limit by option("--limit", help = "Maximum unresolved records to send to Ollama").int().default(100)
    private val dryRun by option("--dry-run", help = "Write the decision audit without changing churches.json").flag()
    private val programmaticOnly by option("--programmatic-only", help = "Run deterministic rules and human overrides without Ollama").flag()

    override fun execute(audit: CrawlCommandAudit) = runBlocking {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        audit.input("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.input("rules", root.resolve("cleanup/denomination-rules.json").toAbsolutePath().normalize())
        audit.setting("model", if (programmaticOnly) "disabled" else model)
        audit.setting("confidence_threshold", threshold)
        audit.setting("limit", limit)
        audit.setting("programmatic_only", programmaticOnly)
        audit.setting("dry_run", dryRun)
        require(limit > 0) { "limit must be positive" }
        if (!programmaticOnly) checkOllamaDiskSpace()
        val rulesFile = root.resolve("cleanup/denomination-rules.json")
        val rules = if (Files.isRegularFile(rulesFile)) json.decodeFromString<List<DenominationRule>>(Files.readString(rulesFile)) else emptyList()
        val denominationCatalogFile = root.resolve("catalog/denominations.json")
        val denominationCatalog = if (Files.isRegularFile(denominationCatalogFile)) {
            json.decodeFromString<List<Denomination>>(Files.readString(denominationCatalogFile))
        } else rules.toDenominationCatalog()
        val entityMatcher = if (programmaticOnly) {
            EntityMatcher { EntityMatchDecision(null, 0.0, reasoning = "LLM disabled") }
        } else KoogOllamaEntityMatcher(model, ollamaUrl)
        val report = PostCrawlCleanup(
            matcher = entityMatcher,
            confidenceThreshold = threshold,
            webpageGuesser = if (programmaticOnly) null else KoogDenominationGuesser(denominationCatalog, model, ollamaUrl),
        )
            .run(
                Path.of(resources),
                limit,
                applyChanges = !dryRun,
                enableLlm = !programmaticOnly,
                cacheRoot = paths.cacheRoot,
            )
        audit.metric("total", report.total)
        audit.metric("not_determined_before", report.notDeterminedBefore)
        audit.metric("not_determined_after", report.notDeterminedAfter)
        audit.metric("programmatic_accepted", report.programmaticAccepted)
        audit.metric("llm_accepted", report.llmAccepted)
        audit.metric("uncertain", report.uncertain)
        audit.metric("human_overrides", report.humanOverrides)
        audit.metric("errors", report.errors)
        audit.output("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.output("decisions", paths.cleanup.resolve("decisions.json").toAbsolutePath().normalize())
        echo(
            "Denominations: NOT_DETERMINED ${report.notDeterminedBefore} -> ${report.notDeterminedAfter}; " +
                "${report.programmaticAccepted} programmatic, ${report.llmAccepted} LLM, " +
                "${report.uncertain} review, ${report.humanOverrides} human, ${report.errors} errors"
        )
    }
}

private class OverrideDenomination : CrawlCommand("override-denomination", CrawlReport.OVERRIDE_DENOMINATION) {
    private val resources by option("--resources").default("resources")
    private val churchId by option("--church-id").required()
    private val denominationId by option("--denomination-id").required()
    private val note by option("--note").default("")

    override fun execute(audit: CrawlCommandAudit) {
        val file = Path.of(resources).resolve("cleanup/human-overrides.json")
        audit.input("church_id", churchId)
        audit.setting("denomination_id", denominationId)
        Files.createDirectories(file.parent)
        val overrides = if (Files.isRegularFile(file)) json.decodeFromString<List<HumanOverride>>(Files.readString(file)) else emptyList()
        val replacement = HumanOverride(churchId, value = denominationId, note = note, reviewedAt = Instant.now().toString())
        Files.writeString(file, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(overrides.filterNot { it.churchId == churchId && it.field == "denominationId" } + replacement))
        audit.metric("prior_overrides", overrides.size)
        audit.metric("resulting_overrides", overrides.count { it.churchId != churchId || it.field != "denominationId" } + 1)
        audit.output("human_overrides", file.toAbsolutePath().normalize())
        echo("Recorded [human-determined] denomination $denominationId for $churchId; run cleanup-llm to apply it")
    }
}

private class LinkSocial : CrawlCommand("link-social", CrawlReport.LINK_SOCIAL) {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default("qwen3:4b")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val threshold by option("--confidence-threshold").double().default(0.80)
    private val limit by option("--limit", help = "Maximum social accounts to resolve").int().default(100)
    private val dryRun by option("--dry-run", help = "Write decisions without changing churches.json").flag()

    override fun execute(audit: CrawlCommandAudit) = runBlocking {
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        audit.input("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.input("social_candidates", paths.cleanup.resolve("social-candidates.json").toAbsolutePath().normalize())
        audit.setting("model", model)
        audit.setting("confidence_threshold", threshold)
        audit.setting("limit", limit)
        audit.setting("dry_run", dryRun)
        checkOllamaDiskSpace()
        val report = SocialLinkPipeline(
            llm = KoogLlmEntitySimilarityMatcher(model, ollamaUrl),
            llmThreshold = threshold.toFloat(),
            modelName = model,
        ).run(
            root,
            limit,
            applyChanges = !dryRun,
            cacheRoot = paths.cacheRoot,
        )
        audit.metric("accounts_processed", report.accountsProcessed)
        audit.metric("direct_links_accepted", report.directLinksAccepted)
        audit.metric("name_links_accepted", report.nameLinksAccepted)
        audit.metric("llm_links_accepted", report.llmLinksAccepted)
        audit.metric("unmatched", report.unmatched)
        audit.output("decisions", paths.cleanup.resolve("social-decisions.json").toAbsolutePath().normalize())
        audit.output("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        echo(
            "Social accounts: ${report.accountsProcessed} processed; ${report.directLinksAccepted} webpage links, " +
                "${report.nameLinksAccepted} exact/containing names, ${report.llmLinksAccepted} LLM, ${report.unmatched} unmatched"
        )
    }
}

private class MergeSocialExports : CrawlCommand("merge-social-exports", CrawlReport.LINK_SOCIAL) {
    private val resources by option("--resources").default("resources")
    private val dryRun by option("--dry-run", help = "Parse, reconcile, and audit without changing churches.json").flag()

    override fun execute(audit: CrawlCommandAudit) {
        val root = Path.of(resources)
        val inputs = SocialExportInputs.load()
        audit.input("catalog", root.resolve("catalog/churches.json").toAbsolutePath().normalize())
        inputs.youtubeSubscribedChannelsCsv?.let { audit.input("youtube_subscriptions", it) }
        inputs.instagramFollowingJson?.let { audit.input("instagram_following", it) }
        inputs.facebookFollowingRawHtml?.let { audit.input("facebook_following_html", it) }
        inputs.facebookFollowingJson?.let { audit.input("facebook_following_json", it) }
        inputs.twitterListMembersJson?.let { audit.input("x_list_members", it) }
        audit.setting("dry_run", dryRun)
        val report = GoogleSocialDataMergePipeline().run(root, inputs, applyChanges = !dryRun)
        audit.metric("google_saved_places", report.googleSavedPlaces)
        audit.metric("social_website_urls_migrated", report.socialWebsiteUrlsMigrated)
        audit.metric("accounts_parsed", report.accountsParsed)
        audit.metric("exact_matches", report.exactMatches)
        audit.metric("estimated_matches", report.estimatedMatches)
        audit.metric("not_matched", report.notMatched)
        audit.metric("excluded", report.excluded)
        audit.output("audit_log", report.auditLog.toAbsolutePath().normalize())
        audit.output("social_candidates", root.resolve("evidence/social-accounts.json").toAbsolutePath().normalize())
        audit.output("social_decisions", root.resolve("cleanup/social-merge-decisions.json").toAbsolutePath().normalize())
        audit.output("catalog", root.resolve("catalog/churches.json").toAbsolutePath().normalize())
        echo(
            "Social exports: ${report.accountsParsed} parsed; ${report.exactMatches} exact, " +
                "${report.estimatedMatches} estimated, ${report.notMatched} unmatched, ${report.excluded} excluded; " +
                "${report.socialWebsiteUrlsMigrated} Google website URLs migrated to social profiles"
        )
    }
}

private class PopulateEnglishNames : CrawlCommand("english-names", CrawlReport.ENGLISH_NAMES) {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default(CAT_TRANSLATE_MODEL)
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val dryRun by option("--dry-run", help = "Resolve and validate every name without changing churches.json").flag()
    private val programmaticOnly by option(
        "--programmatic-only",
        help = "Do not call Ollama; fail if deterministic evidence cannot name every church",
    ).flag()

    override fun execute(audit: CrawlCommandAudit) = runBlocking {
        val startedNanos = System.nanoTime()
        val catalog = Path.of(resources).resolve("catalog/churches.json")
        val paths = CrossmapPaths(Path.of(resources))
        audit.input("catalog", catalog.toAbsolutePath().normalize())
        audit.input("denominations", Path.of(resources).resolve("catalog/denominations.json").toAbsolutePath().normalize())
        audit.setting("model", if (programmaticOnly) "disabled" else model)
        audit.setting("programmatic_only", programmaticOnly)
        audit.setting("dry_run", dryRun)
        require(Files.isRegularFile(catalog)) { "Church catalog does not exist: $catalog" }
        val drafts = json.decodeFromString<List<ChurchRecordDraft>>(Files.readString(catalog))
        val namingInputs = drafts.map(ChurchRecordDraft::toEnglishNameInput)
        val denominations = json.decodeFromString<List<Denomination>>(
            Files.readString(Path.of(resources).resolve("catalog/denominations.json")),
        )
        val dictionaries = ChurchNameEnglishDictionary.load(Path.of(resources))
        val nameGeonames = loadChurchNameGeonames(paths) + dictionaries.geonames
        val detectionOnlyGeonames = loadChurchNameGeoAliases(paths)
        val nameAnalyzer = ChurchNameComponentAnalyzer(
            denominations,
            nameGeonames,
            concepts = dictionaries.concepts,
            detectionOnlyGeonames = detectionOnlyGeonames,
            dictionaryEntries = dictionaries.entries,
        )
        val denominationEnglishNames = runCatching {
            json.decodeFromString<Map<String, String>>(
                Files.readString(Path.of(resources).resolve("catalog/denomination-en-names.json")),
            )
        }.getOrDefault(emptyMap())
        val translationStats = analyzeChurchNames(
            namingInputs, denominations, nameGeonames, detectionOnlyGeonames, dictionaries,
        )
        var englishCache: CachingChurchEnglishNameTranslator? = null
        var componentCache: CachingChurchNameComponentTranslator? = null
        var componentPipeline: ComponentCompletingChurchEnglishNameTranslator? = null
        val translator = if (programmaticOnly) {
            ChurchEnglishNameTranslator { church ->
                error("No deterministic English name for ${church.id} (${church.name})")
            }
        } else {
            checkOllamaDiskSpace()
            val cachedComponents = CachingChurchNameComponentTranslator(
                delegate = FallbackChurchNameComponentTranslator(
                    primary = KoogChurchNameComponentTranslator(model, ollamaUrl),
                    fallback = KoogChurchNameComponentTranslator("qwen3:1.7b", ollamaUrl),
                ),
                model = "$model|typed-components-v5",
                cacheFile = paths.churchNameTranslation.resolve("components.json"),
                batchSize = 32,
            ).also { componentCache = it }
            val componentDelegate = ComponentCompletingChurchEnglishNameTranslator(
                analyzer = nameAnalyzer,
                componentTranslator = cachedComponents,
                fullNameFallback = KoogChurchEnglishNameTranslator(model, ollamaUrl),
                modelName = model,
            ).also { componentPipeline = it }
            CachingChurchEnglishNameTranslator(
                delegate = componentDelegate,
                model = "$model|component-pipeline-v6",
                cacheFile = paths.churchNameTranslation.resolve("whole-names.json"),
                onBatchCompleted = { stats ->
                    echo("English-name LLM progress: ${stats.translated} translated, ${stats.hits} cached, ${stats.batches} batches")
                },
            ).also { englishCache = it }
        }
        val resolver = ChurchEnglishNameResolver(
            ChurchNameEnglishTranslationRules.create(denominations, nameGeonames, dictionaries.concepts),
            translator,
        )
        namingInputs.mapNotNull(resolver::determineProgrammatically).forEach(translationStats::recordProgrammatic)
        val deterministicCount = translationStats.programmaticNames
        try {
            val resolutions = resolver.resolveInputs(namingInputs)
            val determinedAt = java.time.Instant.now().toString()
            val resolved = drafts.map { draft ->
                draft.toChurchRecord(requireNotNull(resolutions[draft.id]), determinedAt)
            }
            val llmCount = resolved.count { church ->
                church.determinations.lastOrNull { it.field == "englishName" }?.source == jp.co.crossmap.DeterminationSource.LLM
            }
            if (!dryRun) {
                val encoded = Json { prettyPrint = true; encodeDefaults = true }.encodeToString(resolved)
                val temporary = Files.createTempFile(catalog.parent, ".churches-english-names-", ".json")
                Files.writeString(temporary, encoded)
                runCatching {
                    Files.move(
                        temporary,
                        catalog,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.getOrElse {
                    Files.move(temporary, catalog, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val cleanupLog = writeDataCleanupStat(
                total = drafts.size,
                deterministic = deterministicCount,
                llm = llmCount,
                startedNanos = startedNanos,
                model = if (programmaticOnly) "disabled" else model,
                errors = englishCache?.stats?.errors ?: 0,
                timeouts = englishCache?.stats?.timeouts ?: 0,
                status = "success",
            )
            translationStats.llmComposedNames = resolved.size - deterministicCount
            translationStats.mergeRuntimeStats(componentPipeline, componentCache)
            englishCache?.stats?.let { cacheStats ->
                translationStats.errors = cacheStats.errors
                translationStats.timeouts = cacheStats.timeouts
            }
            val translationLog = writeChurchNameTranslationLog(translationStats, startedNanos, "success")
            val detailLog = writeLlmComposedNameDetailLog(
                buildLlmComposedNameDetails(
                    inputs = namingInputs,
                    resolutions = resolutions,
                    analyzer = nameAnalyzer,
                    denominations = denominations,
                    denominationEnglishNames = denominationEnglishNames,
                    conceptDictionaryKeys = dictionaries.concepts.keys,
                    specialGeonameDictionaryKeys = dictionaries.geonames.keys,
                    knownGeonames = nameGeonames.keys,
                ),
            )
            audit.metric("total_churches", resolved.size)
            audit.metric("deterministic_names", deterministicCount)
            audit.metric("llm_names", llmCount)
            audit.metric("errors", translationStats.errors)
            audit.metric("timeouts", translationStats.timeouts)
            audit.metric("catalog_updated", !dryRun)
            audit.output("catalog", catalog.toAbsolutePath().normalize())
            audit.output("data_cleanup_log", cleanupLog.toAbsolutePath().normalize())
            audit.output("translation_log", translationLog.toAbsolutePath().normalize())
            audit.output("llm_detail_log", detailLog.toAbsolutePath().normalize())
            echo("Resolved ${resolved.size} English church names${if (dryRun) " (dry run)" else ""} with ${if (programmaticOnly) "deterministic rules" else model}")
        } catch (error: Throwable) {
            val timeout = generateSequence(error) { it.cause }.any { it::class.simpleName.orEmpty().contains("Timeout") }
            val cleanupLog = writeDataCleanupStat(
                total = drafts.size,
                deterministic = deterministicCount,
                llm = 0,
                startedNanos = startedNanos,
                model = if (programmaticOnly) "disabled" else model,
                errors = maxOf(1, englishCache?.stats?.errors ?: 0),
                timeouts = maxOf(if (timeout) 1 else 0, englishCache?.stats?.timeouts ?: 0),
                status = "failed: ${error.message.orEmpty().replace('\n', ' ').take(500)}",
            )
            translationStats.errors = maxOf(1, englishCache?.stats?.errors ?: 0)
            translationStats.timeouts = maxOf(if (timeout) 1 else 0, englishCache?.stats?.timeouts ?: 0)
            translationStats.mergeRuntimeStats(componentPipeline, componentCache)
            val translationLog = writeChurchNameTranslationLog(
                translationStats,
                startedNanos,
                "failed: ${error.message.orEmpty().replace('\n', ' ').take(500)}",
            )
            audit.metric("total_churches", drafts.size)
            audit.metric("deterministic_names", deterministicCount)
            audit.metric("errors", translationStats.errors)
            audit.metric("timeouts", translationStats.timeouts)
            audit.output("data_cleanup_log", cleanupLog.toAbsolutePath().normalize())
            audit.output("translation_log", translationLog.toAbsolutePath().normalize())
            throw error
        }
    }
}

private fun ChurchNameTranslationStats.mergeRuntimeStats(
    pipeline: ComponentCompletingChurchEnglishNameTranslator?,
    cache: CachingChurchNameComponentTranslator?,
) {
    pipeline?.stats?.let { runtime ->
        componentLlmPartsRequested = runtime.componentLlmPartsRequested
        fullNameLlmFallbacks = runtime.fullNameLlmFallbacks
        llmComposedNames = maxOf(llmComposedNames, runtime.llmComposedNames)
    }
    cache?.stats?.let { cacheStats ->
        componentLlmUniqueExecutions = cacheStats.translated
        componentLlmCacheHits = cacheStats.hits
        componentLlmFallbackExecutions = cacheStats.fallbackExecutions
        invalidComponentCacheEntries = cacheStats.invalidCacheEntries
        errors = maxOf(errors, cacheStats.errors)
        timeouts = maxOf(timeouts, cacheStats.timeouts)
    }
}

private class AnalyzeEnglishNames : CrawlCommand("analyze-english-names", CrawlReport.ANALYZE_ENGLISH_NAMES) {
    private val resources by option("--resources").default("resources")

    override fun execute(audit: CrawlCommandAudit) {
        val startedNanos = System.nanoTime()
        val root = Path.of(resources)
        val paths = CrossmapPaths(root)
        audit.input("catalog", paths.churchCatalog.toAbsolutePath().normalize())
        audit.input("denominations", paths.denominationCatalog.toAbsolutePath().normalize())
        val drafts = json.decodeFromString<List<ChurchRecordDraft>>(
            Files.readString(root.resolve("catalog/churches.json")),
        )
        val denominations = json.decodeFromString<List<Denomination>>(
            Files.readString(root.resolve("catalog/denominations.json")),
        )
        val dictionaries = ChurchNameEnglishDictionary.load(root)
        val nameGeonames = loadChurchNameGeonames(paths) + dictionaries.geonames
        val detectionOnlyGeonames = loadChurchNameGeoAliases(paths)
        val inputs = drafts.map(ChurchRecordDraft::toEnglishNameInput)
        val stats = analyzeChurchNames(inputs, denominations, nameGeonames, detectionOnlyGeonames, dictionaries)
        val resolver = ChurchEnglishNameResolver(
            ChurchNameEnglishTranslationRules.create(denominations, nameGeonames, dictionaries.concepts),
            ChurchEnglishNameTranslator { church -> error("Analysis does not invoke an LLM for ${church.id}") },
        )
        inputs.mapNotNull(resolver::determineProgrammatically).forEach(stats::recordProgrammatic)
        val log = writeChurchNameTranslationLog(stats, startedNanos, "analysis-only")
        audit.metric("total_churches", inputs.size)
        audit.metric("programmatic_complete_names", stats.programmaticNames)
        audit.metric("deterministic_parts_translated", stats.deterministicPartsTranslated)
        audit.metric("unresolved_parts", stats.unresolvedParts)
        audit.output("translation_log", log.toAbsolutePath().normalize())
        echo(
            "English-name analysis: ${stats.programmaticNames}/${inputs.size} complete programmatically; " +
                "${stats.deterministicPartsTranslated} deterministic parts, ${stats.unresolvedParts} unresolved parts; $log",
        )
    }
}

private fun analyzeChurchNames(
    inputs: List<ChurchEnglishNameInput>,
    denominations: List<Denomination>,
    geonames: Map<String, String>,
    detectionOnlyGeonames: Set<String>,
    dictionaries: ChurchNameEnglishDictionaries,
): ChurchNameTranslationStats {
    val analyzer = ChurchNameComponentAnalyzer(
        denominations,
        geonames,
        concepts = dictionaries.concepts,
        detectionOnlyGeonames = detectionOnlyGeonames,
        dictionaryEntries = dictionaries.entries,
    )
    return ChurchNameTranslationStats(totalChurches = inputs.size).also { stats ->
        inputs.mapNotNull(analyzer::analyze).forEach(stats::record)
    }
}

private fun loadChurchNameGeonames(paths: CrossmapPaths): Map<String, String> {
    val cleaner = JapaneseGeoNameCleaner.fromCsv(paths.geoNameDuplicatedChurchNames)
    return (ChurchNameEnglishLexicon.geonames + createGeoName(paths).readLexicon(paths.geoNameEnglishLexicon))
        .filterKeys(cleaner::isUsable)
}

private fun loadReviewedChurchGeoNames(paths: CrossmapPaths): Map<String, Map<String, String>> {
    if (!Files.isRegularFile(paths.churchGeoNameTranslations)) return emptyMap()
    return json.decodeFromString<List<ChurchGeoNameTranslation>>(Files.readString(paths.churchGeoNameTranslations))
        .associate { it.ja to it.translations }
}

private fun mergeReviewedChurchGeoNames(
    programmatic: Map<String, Map<String, String>>,
    reviewed: Map<String, Map<String, String>>,
): Map<String, Map<String, String>> = buildMap {
    putAll(programmatic)
    reviewed.forEach { (japanese, translations) ->
        put(japanese, programmatic[japanese].orEmpty() + translations.filterValues(String::isNotBlank))
    }
}

private fun createGeoName(paths: CrossmapPaths): GeoName =
    GeoName(cleaner = JapaneseGeoNameCleaner.fromCsv(paths.geoNameDuplicatedChurchNames))

@Serializable
private data class ChurchNameGeoAlias(
    val name: String,
    val aliases: List<String> = emptyList(),
)

private fun loadChurchNameGeoAliases(paths: CrossmapPaths): Set<String> {
    if (Files.isRegularFile(paths.geonames)) {
        val cleaner = JapaneseGeoNameCleaner.fromCsv(paths.geoNameDuplicatedChurchNames)
        return json.decodeFromString<List<ChurchNameGeoAlias>>(Files.readString(paths.geonames))
            .flatMap { listOf(it.name) + it.aliases }
            .filter { it.length >= 2 && cleaner.isUsable(it) }
            .toSet()
    }
    return emptySet()
}

private fun writeChurchNameTranslationLog(
    stats: ChurchNameTranslationStats,
    startedNanos: Long,
    status: String,
): Path {
    val durationSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
    return CrawlReportLogging.log(
        CrawlReport.CHURCH_NAME_TRANSLATION,
        """
            status=$status
            total_churches=${stats.totalChurches}
            programmatic_complete_names=${stats.programmaticNames}
            denomination_aliases_detected=${stats.denominationAliasesDetected}
            congregation_suffixes_detected=${stats.congregationSuffixesDetected}
            geoname_parts=${stats.geonameParts}
            tradition_parts=${stats.traditionParts}
            conceptual_or_proper_name_parts=${stats.conceptualParts}
            other_parts=${stats.otherParts}
            deterministic_parts_translated=${stats.deterministicPartsTranslated}
            dictionary_parts_translated=${stats.dictionaryPartsTranslated}
            unresolved_parts=${stats.unresolvedParts}
            names_requiring_component_completion=${stats.namesRequiringComponentCompletion}
            component_llm_parts_requested=${stats.componentLlmPartsRequested}
            component_llm_unique_executions=${stats.componentLlmUniqueExecutions}
            component_llm_cache_hits=${stats.componentLlmCacheHits}
            component_llm_fallback_executions=${stats.componentLlmFallbackExecutions}
            invalid_component_cache_entries=${stats.invalidComponentCacheEntries}
            full_name_llm_fallbacks=${stats.fullNameLlmFallbacks}
            llm_composed_names=${stats.llmComposedNames}
            errors=${stats.errors}
            timeouts=${stats.timeouts}
            duration_seconds=${"%.3f".format(java.util.Locale.ROOT, durationSeconds)}
        """.trimIndent() + "\n" +
            stats.programmaticRuleCounts.entries.sortedByDescending(Map.Entry<String, Int>::value).joinToString("\n") {
                "programmatic_rule.${it.key.toLogKey()}=${it.value}"
            } + "\n" +
            stats.unresolvedPartCounts.entries.sortedByDescending(Map.Entry<String, Int>::value).take(50)
                .mapIndexed { index, entry ->
                    "unresolved_part_${(index + 1).toString().padStart(2, '0')}=${entry.key}|${entry.value}"
                }.joinToString("\n"),
    )
}

private fun String.toLogKey(): String = lowercase()
    .replace(Regex("""[^a-z0-9]+"""), "_")
    .trim('_')
    .take(100)

private fun writeDataCleanupStat(
    total: Int,
    deterministic: Int,
    llm: Int,
    startedNanos: Long,
    model: String,
    errors: Int,
    timeouts: Int,
    status: String,
): Path {
    val durationSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
    return CrawlReportLogging.log(
        CrawlReport.DATA_CLEANUP_STAT,
        """
            status=$status
            total_churches=$total
            deterministic_translations=$deterministic
            llm_translations=$llm
            unresolved=${total - deterministic - llm}
            llm_model=$model
            errors=$errors
            llm_timeouts=$timeouts
            duration_seconds=${"%.3f".format(java.util.Locale.ROOT, durationSeconds)}
            churches_per_second=${"%.3f".format(java.util.Locale.ROOT, total / durationSeconds.coerceAtLeast(0.001))}
        """.trimIndent(),
    )
}

@Serializable
private data class DenominationNameInput(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val officialWebsite: String = "",
)

private class PopulateDenominationEnglishNames : CrawlCommand("denomination-english-names", CrawlReport.DENOMINATION_ENGLISH_NAMES) {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default(CAT_TRANSLATE_MODEL)
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")

    override fun execute(audit: CrawlCommandAudit) = runBlocking {
        val catalogDirectory = Path.of(resources).resolve("catalog")
        val source = catalogDirectory.resolve("denominations.json")
        val output = catalogDirectory.resolve("denomination-en-names.json")
        audit.input("denominations", source.toAbsolutePath().normalize())
        audit.setting("model", model)
        val denominations = json.decodeFromString<List<DenominationNameInput>>(Files.readString(source))
            .filterNot { it.id.equals("NOT_DETERMINED", true) || it.id.equals("INDEPENDENT", true) }
        val deterministic = denominations.mapNotNull { denomination ->
            val alias = denomination.aliases.firstOrNull(::isLatinScriptText)
            val officialDomainAcronym = runCatching { java.net.URI(denomination.officialWebsite).host }
                .getOrNull()
                ?.removePrefix("www.")
                ?.substringBefore('.')
                ?.takeIf { it.matches(Regex("""[A-Za-z]{3,8}""")) }
            (alias ?: officialDomainAcronym)?.let { denomination.id to sanitizeLatinText(it) }
        }.toMap()
        val unresolved = denominations.filterNot { it.id in deterministic }
        if (unresolved.isNotEmpty()) checkOllamaDiskSpace()
        val translations = KoogJapaneseTextTranslator(model, ollamaUrl).translateAll(unresolved.map { it.name })
        val translated = unresolved.zip(translations).associate { (denomination, value) ->
            val englishName = sanitizeLatinText(value)
            require(englishName.isNotBlank()) { "No English denomination name for ${denomination.id} (${denomination.name})" }
            denomination.id to englishName
        }
        val names = (deterministic + translated).toSortedMap()
        require(names.size == denominations.size) { "Not every denomination received an English name" }
        Files.createDirectories(output.parent)
        val temporary = Files.createTempFile(output.parent, ".denomination-english-names-", ".json")
        Files.writeString(temporary, Json { prettyPrint = true }.encodeToString<Map<String, String>>(names.toMap()))
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        audit.metric("denominations", denominations.size)
        audit.metric("deterministic_names", deterministic.size)
        audit.metric("llm_names", translated.size)
        audit.output("english_names", output.toAbsolutePath().normalize())
        echo("Resolved ${names.size} English denomination names")
    }

    private fun isLatinScriptText(value: String): Boolean =
        value.any(Char::isLetter) && !Regex("""[\u3040-\u30ff\u3400-\u9fff]""").containsMatchIn(value)

    private fun sanitizeLatinText(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""['’]"""), "")
        .replace("&", " and ")
        .replace(Regex("""[^A-Za-z0-9 .-]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.', '-')
}

fun main(args: Array<String>) = Crawl().subcommands(
    ReadGoogleSavedPlaces(), ResolveGoogleSavedPlaces(), PromoteGoogleSavedPlaces(), Refresh(), CrawlDenominationDirectories(), BuildGeonames(), CleanupLlm(), OverrideDenomination(), MergeSocialExports(), LinkSocial(),
    PopulateEnglishNames(), AnalyzeEnglishNames(), PopulateDenominationEnglishNames(), PrepareGeoNameCache(), BuildChurchGeonames(), NormalizeAddresses(), BuildSnapshot(),
    CatalogNeo4jHealth(), CatalogNeo4jMigrate(), CatalogNeo4jImport(), CatalogNeo4jExport(), CatalogNeo4jParity(), CatalogNeo4jIntegrity(),
).main(args)

package jp.co.crossmap.crawl

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.double
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import jp.co.crossmap.ChurchRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private class Crawl : CliktCommand(name = "crossmap-crawl") {
    override fun run() = Unit
}

private class ReadGoogleSavedPlaces : CliktCommand(name = "read-google-saved-places") {
    private val input by option("--input", help = "Google Takeout/Saved directory containing CSV lists").required()
    private val resources by option("--resources").default("resources")

    override fun run() {
        val rawDirectory = Path.of(resources).resolve("raw/google-saved-places")
        val excludedUrls = GoogleSavedPlacesSeedReader.readExcludedUrls(
            listOf(
                rawDirectory.resolve("exclusions/church-exclude.csv"),
                rawDirectory.resolve("exclusions/catholic-exclude.csv"),
            ),
        )
        val report = GoogleSavedPlacesSeedReader().readDirectory(
            inputDirectory = Path.of(input),
            output = rawDirectory.resolve("seeds.json"),
            includedLists = GoogleSavedPlacesSeedReader.GMAP_DEFAULT_LISTS,
            excludedUrls = excludedUrls,
        )
        Files.writeString(
            rawDirectory.resolve("seed-read-report.json"),
            Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report),
        )
        echo(
            "Read ${report.rowsRead} rows from ${report.filesRead} lists: " +
                "${report.seedsWritten} unique Google places, ${report.duplicatesMerged} duplicates, ${report.errors.size} errors",
        )
    }
}

private class ResolveGoogleSavedPlaces : CliktCommand(name = "resolve-google-saved-places") {
    private val resources by option("--resources").default("resources")
    private val concurrency by option("--concurrency").int().default(6)
    private val offline by option("--offline", help = "Require copied Google CID cache; never send requests").flag()

    override fun run() {
        val root = Path.of(resources)
        val report = GoogleMapsPlaceResolver(
            pageSource = CachedGoogleMapsPageSource(root, allowNetwork = !offline),
            maxConcurrency = concurrency,
        ).resolve(root)
        echo(
            "Resolved ${report.candidates}/${report.seeds} Google places: ${report.cacheHits} cache hits, " +
                "${report.fetched} fetched, ${report.catholicNonChurchesFiltered} Catholic non-churches filtered, " +
                "${report.errors.size} errors",
        )
    }
}

private class PromoteGoogleSavedPlaces : CliktCommand(name = "promote-google-saved-places") {
    private val resources by option("--resources").default("resources")
    private val englishModel by option("--english-model").default(CAT_TRANSLATE_MODEL)
    private val denominationModel by option("--denomination-model").default("qwen3:1.7b")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val threshold by option("--confidence-threshold").double().default(0.80)
    private val limit by option("--limit", help = "Maximum unresolved denominations to send to Ollama").int().default(100)
    private val programmaticOnly by option("--programmatic-only", help = "Disable every Ollama fallback").flag()
    private val skipWebsiteRefresh by option("--skip-website-refresh").flag()
    private val skipDirectoryCrawl by option("--skip-directory-crawl").flag()
    private val dryRun by option("--dry-run", help = "Complete the pending catalog but do not replace churches.json").flag()

    override fun run() = runBlocking {
        require(limit > 0) { "limit must be positive" }
        val startedNanos = System.nanoTime()
        val root = Path.of(resources)
        if (!programmaticOnly) checkOllamaDiskSpace()
        val denominations = json.decodeFromString<List<Denomination>>(
            Files.readString(root.resolve("catalog/denominations.json")),
        )
        val denominationMatcher = if (programmaticOnly) {
            EntityMatcher { EntityMatchDecision(null, 0.0, reasoning = "Ollama disabled") }
        } else {
            KoogOllamaEntityMatcher(denominationModel, ollamaUrl)
        }
        var englishCache: CachingChurchEnglishNameTranslator? = null
        val translator = if (programmaticOnly) {
            ChurchEnglishNameTranslator { church ->
                error("No deterministic English name for ${church.id} (${church.name})")
            }
        } else {
            CachingChurchEnglishNameTranslator(
                delegate = KoogChurchEnglishNameTranslator(englishModel, ollamaUrl),
                model = englishModel,
                cacheFile = root.resolve("cleanup/english-name-llm-cache.json"),
                onBatchCompleted = { stats ->
                    echo("English-name LLM progress: ${stats.translated} translated, ${stats.hits} cached, ${stats.batches} batches")
                },
            ).also { englishCache = it }
        }
        val englishResolver = ChurchEnglishNameResolver(
            ChurchNameEnglishTranslationRules.create(denominations),
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
        ).run(
            resourcesRoot = root,
            llmLimit = limit,
            enableLlm = !programmaticOnly,
            refreshWebsites = !skipWebsiteRefresh,
            crawlDirectories = !skipDirectoryCrawl,
            promote = !dryRun,
        )
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

private class BuildGeonames : CliktCommand(name = "build-geonames") {
    private val resources by option("--resources").default("resources")
    private val citiesSource by option("--cities-source").required()
    override fun run() {
        val root = Path.of(resources)
        val churches = json.decodeFromString<List<ChurchRecord>>(java.nio.file.Files.readString(root.resolve("catalog/churches.json")))
        val result = GeoCatalogBuilder().build(churches, Path.of(citiesSource), root.resolve("geonames/japan.json"))
        echo("Generated ${result.size} geonames")
    }
}

private class BuildSnapshot : CliktCommand(name = "build-snapshot") {
    private val resources by option("--resources").default("resources")
    private val version by option("--version").default(Instant.now().toString().replace(":", "-").substringBefore('.'))
    override fun run() {
        val manifest = SnapshotBuilder().build(Path.of(resources), version)
        echo("Built ${manifest.documentCount}-church snapshot ${manifest.indexVersion} (${manifest.sha256})")
    }
}

private class CrawlDenominationDirectories : CliktCommand(name = "crawl-denomination-directories") {
    private val resources by option("--resources").default("resources")
    override fun run() {
        val report = OfficialDirectoryCrawler().crawl(Path.of(resources))
        echo("Crawled ${report.sources} denomination sources / ${report.pages} pages: ${report.candidates} candidates, ${report.errors} errors")
    }
}

private class Refresh : CliktCommand(name = "refresh") {
    private val resources by option("--resources").default("resources")
    private val concurrency by option("--max-concurrency").int().default(6)
    override fun run() {
        val report = WebsiteRefresher(concurrency).refresh(Path.of(resources))
        echo("Refreshed ${report.churches} churches: ${report.fetched} fetched, ${report.unchanged} unchanged, ${report.errors} errors")
    }
}

private class CleanupLlm : CliktCommand(name = "cleanup-llm") {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default("qwen3:4b")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val threshold by option("--confidence-threshold").double().default(0.80)
    private val limit by option("--limit", help = "Maximum unresolved records to send to Ollama").int().default(100)
    private val dryRun by option("--dry-run", help = "Write the decision audit without changing churches.json").flag()
    private val programmaticOnly by option("--programmatic-only", help = "Run deterministic rules and human overrides without Ollama").flag()

    override fun run() = runBlocking {
        require(limit > 0) { "limit must be positive" }
        val root = Path.of(resources)
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
            .run(Path.of(resources), limit, applyChanges = !dryRun, enableLlm = !programmaticOnly)
        echo(
            "Denominations: NOT_DETERMINED ${report.notDeterminedBefore} -> ${report.notDeterminedAfter}; " +
                "${report.programmaticAccepted} programmatic, ${report.llmAccepted} LLM, " +
                "${report.uncertain} review, ${report.humanOverrides} human, ${report.errors} errors"
        )
    }
}

private class OverrideDenomination : CliktCommand(name = "override-denomination") {
    private val resources by option("--resources").default("resources")
    private val churchId by option("--church-id").required()
    private val denominationId by option("--denomination-id").required()
    private val note by option("--note").default("")

    override fun run() {
        val file = Path.of(resources).resolve("cleanup/human-overrides.json")
        Files.createDirectories(file.parent)
        val overrides = if (Files.isRegularFile(file)) json.decodeFromString<List<HumanOverride>>(Files.readString(file)) else emptyList()
        val replacement = HumanOverride(churchId, value = denominationId, note = note, reviewedAt = Instant.now().toString())
        Files.writeString(file, Json { prettyPrint = true; encodeDefaults = true }.encodeToString(overrides.filterNot { it.churchId == churchId && it.field == "denominationId" } + replacement))
        echo("Recorded [human-determined] denomination $denominationId for $churchId; run cleanup-llm to apply it")
    }
}

private class LinkSocial : CliktCommand(name = "link-social") {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default("qwen3:4b")
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val threshold by option("--confidence-threshold").double().default(0.80)
    private val limit by option("--limit", help = "Maximum social accounts to resolve").int().default(100)
    private val dryRun by option("--dry-run", help = "Write decisions without changing churches.json").flag()

    override fun run() = runBlocking {
        checkOllamaDiskSpace()
        val report = SocialLinkPipeline(
            llm = KoogLlmEntitySimilarityMatcher(model, ollamaUrl),
            llmThreshold = threshold.toFloat(),
            modelName = model,
        ).run(Path.of(resources), limit, applyChanges = !dryRun)
        echo(
            "Social accounts: ${report.accountsProcessed} processed; ${report.directLinksAccepted} webpage links, " +
                "${report.nameLinksAccepted} exact/containing names, ${report.llmLinksAccepted} LLM, ${report.unmatched} unmatched"
        )
    }
}

private class PopulateEnglishNames : CliktCommand(name = "english-names") {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default(CAT_TRANSLATE_MODEL)
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")
    private val dryRun by option("--dry-run", help = "Resolve and validate every name without changing churches.json").flag()
    private val programmaticOnly by option(
        "--programmatic-only",
        help = "Do not call Ollama; fail if deterministic evidence cannot name every church",
    ).flag()

    override fun run() = runBlocking {
        val startedNanos = System.nanoTime()
        val catalog = Path.of(resources).resolve("catalog/churches.json")
        require(Files.isRegularFile(catalog)) { "Church catalog does not exist: $catalog" }
        val drafts = json.decodeFromString<List<ChurchRecordDraft>>(Files.readString(catalog))
        val namingInputs = drafts.map(ChurchRecordDraft::toEnglishNameInput)
        val denominations = json.decodeFromString<List<Denomination>>(
            Files.readString(Path.of(resources).resolve("catalog/denominations.json")),
        )
        var englishCache: CachingChurchEnglishNameTranslator? = null
        val translator = if (programmaticOnly) {
            ChurchEnglishNameTranslator { church ->
                error("No deterministic English name for ${church.id} (${church.name})")
            }
        } else {
            checkOllamaDiskSpace()
            CachingChurchEnglishNameTranslator(
                delegate = KoogChurchEnglishNameTranslator(model, ollamaUrl),
                model = model,
                cacheFile = Path.of(resources).resolve("cleanup/english-name-llm-cache.json"),
                onBatchCompleted = { stats ->
                    echo("English-name LLM progress: ${stats.translated} translated, ${stats.hits} cached, ${stats.batches} batches")
                },
            ).also { englishCache = it }
        }
        val resolver = ChurchEnglishNameResolver(ChurchNameEnglishTranslationRules.create(denominations), translator)
        val deterministicCount = namingInputs.count { resolver.determineProgrammatically(it) != null }
        try {
            val resolutions = resolver.resolveInputs(namingInputs)
            val determinedAt = java.time.Instant.now().toString()
            val resolved = drafts.map { draft ->
                draft.toChurchRecord(requireNotNull(resolutions[draft.id]), determinedAt)
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
            writeDataCleanupStat(
                total = drafts.size,
                deterministic = deterministicCount,
                llm = resolved.count { church ->
                    church.determinations.lastOrNull { it.field == "englishName" }?.source == jp.co.crossmap.DeterminationSource.LLM
                },
                startedNanos = startedNanos,
                model = if (programmaticOnly) "disabled" else model,
                errors = englishCache?.stats?.errors ?: 0,
                timeouts = englishCache?.stats?.timeouts ?: 0,
                status = "success",
            )
            echo("Resolved ${resolved.size} English church names${if (dryRun) " (dry run)" else ""} with ${if (programmaticOnly) "deterministic rules" else model}")
        } catch (error: Throwable) {
            val timeout = generateSequence(error) { it.cause }.any { it::class.simpleName.orEmpty().contains("Timeout") }
            writeDataCleanupStat(
                total = drafts.size,
                deterministic = deterministicCount,
                llm = 0,
                startedNanos = startedNanos,
                model = if (programmaticOnly) "disabled" else model,
                errors = maxOf(1, englishCache?.stats?.errors ?: 0),
                timeouts = maxOf(if (timeout) 1 else 0, englishCache?.stats?.timeouts ?: 0),
                status = "failed: ${error.message.orEmpty().replace('\n', ' ').take(500)}",
            )
            throw error
        }
    }
}

private fun writeDataCleanupStat(
    total: Int,
    deterministic: Int,
    llm: Int,
    startedNanos: Long,
    model: String,
    errors: Int,
    timeouts: Int,
    status: String,
) {
    val durationSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
    val workingDirectory = Path.of("").toAbsolutePath().normalize()
    val projectRoot = generateSequence(workingDirectory) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        ?: workingDirectory
    val logs = projectRoot.resolve("logs")
    Files.createDirectories(logs)
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm"))
    var destination = logs.resolve("$timestamp-data-cleanup-stat.log")
    var sequence = 2
    while (Files.exists(destination)) {
        destination = logs.resolve("$timestamp-$sequence-data-cleanup-stat.log")
        sequence++
    }
    Files.writeString(
        destination,
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
        """.trimIndent() + "\n",
    )
    println("Data cleanup statistics: $destination")
}

@Serializable
private data class DenominationNameInput(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val officialWebsite: String = "",
)

private class PopulateDenominationEnglishNames : CliktCommand(name = "denomination-english-names") {
    private val resources by option("--resources").default("resources")
    private val model by option("--model").default(CAT_TRANSLATE_MODEL)
    private val ollamaUrl by option("--ollama-url").default("http://localhost:11434")

    override fun run() = runBlocking {
        val catalogDirectory = Path.of(resources).resolve("catalog")
        val source = catalogDirectory.resolve("denominations.json")
        val output = catalogDirectory.resolve("denomination-english-names.json")
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
        Files.writeString(temporary, Json { prettyPrint = true }.encodeToString(names))
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
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
    ReadGoogleSavedPlaces(), ResolveGoogleSavedPlaces(), PromoteGoogleSavedPlaces(), Refresh(), CrawlDenominationDirectories(), BuildGeonames(), CleanupLlm(), OverrideDenomination(), LinkSocial(),
    PopulateEnglishNames(), PopulateDenominationEnglishNames(), BuildSnapshot(),
).main(args)

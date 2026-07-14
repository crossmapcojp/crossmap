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
import java.time.Instant
import jp.co.crossmap.ChurchRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

private class Crawl : CliktCommand(name = "crossmap-crawl") {
    override fun run() = Unit
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

fun main(args: Array<String>) = Crawl().subcommands(
    Refresh(), CrawlDenominationDirectories(), BuildGeonames(), CleanupLlm(), OverrideDenomination(), LinkSocial(), BuildSnapshot(),
).main(args)

package jp.co.crossmap.crawl

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.github.ajalt.clikt.core.CliktCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.slf4j.MDC

internal enum class CrawlReport(val fileSuffix: String) {
    DATA_CLEANUP_STAT("data-cleanup-stat"),
    CHURCH_NAME_TRANSLATION("church-name-translation"),
    LLM_COMPOSED_NAME_DETAIL("llm-composed-name-detail"),
    READ_GOOGLE_SAVED_PLACES("read-google-saved-places"),
    RESOLVE_GOOGLE_SAVED_PLACES("resolve-google-saved-places"),
    PROMOTE_GOOGLE_SAVED_PLACES("promote-google-saved-places"),
    BUILD_GEONAMES("build-geonames"),
    CHURCH_GEONAMES("church-geonames"),
    GEONAME_TRANSLATION_COVERAGE("geoname-translation-coverage"),
    PREPARE_GEONAME_CACHE("prepare-geoname-cache"),
    ADDRESS_NORMALIZATION("address-normalization"),
    BUILD_SNAPSHOT("build-snapshot"),
    CRAWL_DENOMINATION_DIRECTORIES("crawl-denomination-directories"),
    REFRESH("refresh"),
    CLEANUP_LLM("cleanup-llm"),
    OVERRIDE_DENOMINATION("override-denomination"),
    LINK_SOCIAL("link-social"),
    ENGLISH_NAMES("english-names"),
    ANALYZE_ENGLISH_NAMES("analyze-english-names"),
    DENOMINATION_ENGLISH_NAMES("denomination-english-names"),
}

internal abstract class CrawlCommand(
    name: String,
    private val report: CrawlReport,
) : CliktCommand(name = name) {
    final override fun run() {
        val audit = CrawlCommandAudit(report)
        try {
            execute(audit)
            audit.finish("success")
        } catch (error: Throwable) {
            audit.finish("failed", error)
            throw error
        }
    }

    protected abstract fun execute(audit: CrawlCommandAudit)
}

internal class CrawlCommandAudit(private val report: CrawlReport) {
    private val startedAt = java.time.Instant.now()
    private val startedNanos = System.nanoTime()
    private val inputs = linkedMapOf<String, String>()
    private val settings = linkedMapOf<String, String>()
    private val metrics = linkedMapOf<String, String>()
    private val outputs = linkedMapOf<String, String>()
    private val details = mutableListOf<Pair<String, String>>()
    private val blocks = mutableListOf<String>()

    fun input(name: String, value: Any?) = put(inputs, name, value)
    fun setting(name: String, value: Any?) = put(settings, name, value)
    fun metric(name: String, value: Any?) = put(metrics, name, value)
    fun output(name: String, value: Any?) = put(outputs, name, value)
    fun detail(name: String, value: Any?) {
        details += name.logKey() to value.logValue()
    }
    fun block(value: String) {
        value.trim().takeIf(String::isNotBlank)?.let(blocks::add)
    }

    internal fun finish(status: String, error: Throwable? = null): Path {
        val finishedAt = java.time.Instant.now()
        val durationSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        val content = buildString {
            appendLine("---")
            appendLine("command=${report.fileSuffix}")
            appendLine("status=$status")
            appendLine("started_at=$startedAt")
            appendLine("finished_at=$finishedAt")
            appendLine("duration_seconds=${"%.3f".format(java.util.Locale.ROOT, durationSeconds)}")
            inputs.forEach { (name, value) -> appendLine("input.$name=$value") }
            settings.forEach { (name, value) -> appendLine("setting.$name=$value") }
            metrics.forEach { (name, value) -> appendLine("metric.$name=$value") }
            outputs.forEach { (name, value) -> appendLine("output.$name=$value") }
            details.forEach { (name, value) -> appendLine("detail.$name=$value") }
            error?.let {
                appendLine("error.type=${it::class.qualifiedName.orEmpty().logValue()}")
                appendLine("error.message=${it.message.orEmpty().logValue()}")
            }
            blocks.forEach { block ->
                appendLine()
                appendLine(block)
            }
        }
        return CrawlReportLogging.log(report, content)
    }

    private fun put(destination: MutableMap<String, String>, name: String, value: Any?) {
        destination[name.logKey()] = value.logValue()
    }

    private fun String.logKey(): String = lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "_")
        .trim('_')
        .ifBlank { "value" }

    private fun Any?.logValue(): String = toString().replace('\n', ' ').replace('\r', ' ').trim()
}

internal object CrawlReportLogging {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")
    private val logger by lazy { LoggerFactory.getLogger("jp.co.crossmap.crawl.report") }

    @Volatile
    private var configuration: Configuration? = null

    @Synchronized
    fun configure(
        logsDirectory: Path = projectLogsDirectory(),
        now: LocalDateTime = LocalDateTime.now(),
        force: Boolean = false,
    ) {
        val normalizedDirectory = logsDirectory.toAbsolutePath().normalize()
        val timestamp = now.format(timestampFormat)
        if (!force && configuration == Configuration(normalizedDirectory, timestamp)) return

        Files.createDirectories(normalizedDirectory)
        System.setProperty("crossmap.log.dir", normalizedDirectory.toString())
        System.setProperty("crossmap.log.timestamp", timestamp)

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.reset()
        val resource = requireNotNull(javaClass.classLoader.getResource("logback.xml")) {
            "crawl logback.xml is missing from the runtime classpath"
        }
        JoranConfigurator().apply { this.context = context }.doConfigure(resource)
        configuration = Configuration(normalizedDirectory, timestamp)
    }

    @Synchronized
    fun configureIfNeeded(
        logsDirectory: Path = projectLogsDirectory(),
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val normalizedDirectory = logsDirectory.toAbsolutePath().normalize()
        if (configuration?.logsDirectory != normalizedDirectory) configure(normalizedDirectory, now)
    }

    fun log(report: CrawlReport, content: String): Path {
        if (configuration == null) configure()
        val current = requireNotNull(configuration)
        MDC.putCloseable("crossmapReport", report.fileSuffix).use {
            logger.info(content.trimEnd())
        }
        return current.logsDirectory.resolve("${current.timestamp}-${report.fileSuffix}.log")
    }

    private data class Configuration(val logsDirectory: Path, val timestamp: String)
}

internal fun projectLogsDirectory(): Path {
    val workingDirectory = Path.of("").toAbsolutePath().normalize()
    val projectRoot = generateSequence(workingDirectory) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        ?: workingDirectory
    return projectRoot.resolve("logs")
}

package jp.co.crossmap.crawl

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrawlReportLoggingTest {
    @Test
    fun definesAReportForEveryCommandEntryPoint() {
        val specializedReports = setOf(
            CrawlReport.DATA_CLEANUP_STAT,
            CrawlReport.CHURCH_NAME_TRANSLATION,
            CrawlReport.LLM_COMPOSED_NAME_DETAIL,
            CrawlReport.GEONAME_TRANSLATION_COVERAGE,
        )
        assertEquals(
            setOf(
                "read-google-saved-places",
                "resolve-google-saved-places",
                "promote-google-saved-places",
                "refresh",
                "crawl-denomination-directories",
                "cleanup-llm",
                "override-denomination",
                "link-social",
                "english-names",
                "analyze-english-names",
                "denomination-english-names",
                "build-geonames",
                "prepare-geoname-cache",
                "church-geonames",
                "address-normalization",
                "build-snapshot",
            ),
            (CrawlReport.entries - specializedReports).map(CrawlReport::fileSuffix).toSet(),
        )
    }

    @Test
    fun routesEveryReportToItsTimestampedFileAndConsole() {
        val logs = Files.createTempDirectory("crossmap-logback-reports")
        val originalOut = System.out
        val consoleBytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(consoleBytes, true, StandardCharsets.UTF_8))
            CrawlReportLogging.configure(
                logsDirectory = logs,
                now = LocalDateTime.of(2026, 7, 16, 9, 5),
                force = true,
            )

            val paths = CrawlReport.entries.associateWith { report ->
                CrawlReportLogging.log(report, "report=${report.fileSuffix}")
            }

            CrawlReport.entries.forEach { report ->
                val path = paths.getValue(report)
                assertEquals("2026-07-16-09-05-${report.fileSuffix}.log", path.fileName.toString())
                assertEquals("report=${report.fileSuffix}\n", Files.readString(path))
                assertTrue(consoleBytes.toString(StandardCharsets.UTF_8).contains("report=${report.fileSuffix}"))
            }
        } finally {
            System.setOut(originalOut)
            CrawlReportLogging.configure(force = true)
            logs.toFile().deleteRecursively()
        }
    }

    @Test
    fun commandBaseWritesReviewFieldsForSuccessAndFailure() {
        val logs = Files.createTempDirectory("crossmap-command-audit")
        try {
            CrawlReportLogging.configure(logs, LocalDateTime.of(2026, 7, 16, 10, 15), force = true)
            object : CrawlCommand("successful-command", CrawlReport.BUILD_GEONAMES) {
                override fun execute(audit: CrawlCommandAudit) {
                    audit.input("source", "cities.csv")
                    audit.setting("mode", "offline")
                    audit.metric("records", 47)
                    audit.output("catalog", "geonames/japan.json")
                }
            }.run()

            val success = Files.readString(logs.resolve("2026-07-16-10-15-build-geonames.log"))
            assertTrue(success.contains("command=build-geonames"))
            assertTrue(success.contains("status=success"))
            assertTrue(success.contains("input.source=cities.csv"))
            assertTrue(success.contains("setting.mode=offline"))
            assertTrue(success.contains("metric.records=47"))
            assertTrue(success.contains("output.catalog=geonames/japan.json"))

            assertFailsWith<IllegalStateException> {
                object : CrawlCommand("failing-command", CrawlReport.REFRESH) {
                    override fun execute(audit: CrawlCommandAudit) {
                        audit.input("catalog", "churches.json")
                        error("fixture failure")
                    }
                }.run()
            }
            val failure = Files.readString(logs.resolve("2026-07-16-10-15-refresh.log"))
            assertTrue(failure.contains("status=failed"))
            assertTrue(failure.contains("error.type=java.lang.IllegalStateException"))
            assertTrue(failure.contains("error.message=fixture failure"))
        } finally {
            CrawlReportLogging.configure(force = true)
            logs.toFile().deleteRecursively()
        }
    }
}

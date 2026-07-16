package jp.co.crossmap.crawl

import java.time.Duration
import jp.co.crossmap.LightPanda
import jp.co.crossmap.ProcessLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestLightPanda {
    @Test
    fun invokesFetchDumpHtmlAndReturnsRenderedDocument() {
        var invoked = emptyList<String>()
        val lightPanda = LightPanda(
            binary = "/opt/lightpanda",
            timeout = Duration.ofSeconds(2),
            renderWait = Duration.ofSeconds(1),
            launcher = ProcessLauncher { command ->
                invoked = command
                ProcessBuilder("/bin/sh", "-c", "printf '<html><body>東京教会</body></html>'").start()
            },
        )

        val html = lightPanda.fetchHtml("https://www.google.com/maps?cid=123")

        assertEquals("<html><body>東京教会</body></html>", html)
        assertEquals(
            listOf(
                "/opt/lightpanda",
                "fetch",
                "--dump",
                "html",
                "--wait-ms",
                "1000",
                "https://www.google.com/maps?cid=123",
            ),
            invoked,
        )
    }

    @Test
    fun exposesExitCodeAndBoundedStderr() {
        val lightPanda = LightPanda(
            launcher = ProcessLauncher {
                ProcessBuilder("/bin/sh", "-c", "printf 'navigation failed' >&2; exit 7").start()
            },
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            lightPanda.fetchHtml("https://www.google.com/maps?cid=123")
        }

        assertTrue(failure.message.orEmpty().contains("exited 7"))
        assertTrue(failure.message.orEmpty().contains("navigation failed"))
    }

    @Test
    fun rejectsNonHttpInputBeforeStartingAProcess() {
        assertFailsWith<IllegalArgumentException> { LightPanda().fetchHtml("file:///etc/passwd") }
    }

    @Test
    fun fetchesRenderedHtmlWithTheInstalledLightpandaWhenEnabled() {
        if (System.getenv("CROSSMAP_LIGHTPANDA_INTEGRATION") != "1") return
        val html = LightPanda(timeout = Duration.ofSeconds(20)).fetchHtml("https://example.com")
        assertTrue(html.contains("Example Domain"))
    }
}

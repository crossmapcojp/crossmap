package jp.co.crossmap.crawl

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import java.io.Closeable

/**
 * Singleton-per-process headless Chromium browser via Playwright.
 *
 * The Playwright process and browser are started lazily on first use and shared across
 * all [fetchHtml] calls. Each call creates an isolated browser context (no shared cookies
 * or session state). Implements [Closeable] so callers can release the native process.
 */
class PlaywrightBrowser : Closeable {

    @Volatile
    private var playwright: Playwright? = null

    @Volatile
    private var browser: Browser? = null

    private fun ensureStarted() {
        if (browser != null) return
        synchronized(this) {
            if (browser != null) return
            val pw = Playwright.create()
            val br = pw.chromium().launch(
                BrowserType.LaunchOptions().apply {
                    headless = true
                },
            )
            playwright = pw
            browser = br
        }
    }

    /**
     * Fetch the fully-rendered HTML for [url] using a headless Chromium browser.
     *
     * A fresh browser context is created for each call so that pages are isolated
     * (no cookie/state leakage between requests).
     *
     * For Google Maps URLs, waits for the redirect to settle and the page content to load.
     */
    fun fetchHtml(url: String): String {
        ensureStarted()
        val br = browser ?: error("Playwright browser not initialised")
        val context = br.newContext()
        return try {
            val page = context.newPage()
            page.navigate(url, Page.NavigateOptions().apply {
                waitUntil = WaitUntilState.DOMCONTENTLOADED
                timeout = 30_000.0
            })
            if (isGoogleMapsUrl(url)) {
                waitForGoogleMaps(page)
            } else {
                page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().apply {
                    timeout = 15_000.0
                })
            }
            val html = page.content()
                ?: error("Playwright returned null content for $url")
            html
        } finally {
            context.close()
        }
    }

    private fun isGoogleMapsUrl(url: String): Boolean =
        url.contains("google.com/maps") || url.contains("google.co.jp/maps")

    private fun waitForGoogleMaps(page: Page) {
        // Wait for the CID redirect to settle — the URL should contain /place/
        try {
            page.waitForFunction(
                "() => window.location.href.includes('/place/')",
                null,
                Page.WaitForFunctionOptions().apply { timeout = 20_000.0 },
            )
        } catch (_: Exception) {
            // Might already be on the place page, or redirect didn't happen — continue anyway
        }
        // Wait for the page to have meaningful content (og:title or place name)
        try {
            page.waitForFunction(
                """() => {
                    const og = document.querySelector('meta[property="og:title"]');
                    if (og && og.content) return true;
                    return document.title.length > 5 && !document.title.includes('Google Maps');
                }""",
                null,
                Page.WaitForFunctionOptions().apply { timeout = 15_000.0 },
            )
        } catch (_: Exception) {
            // Continue with whatever we have
        }
        // Give extra time for JavaScript-rendered content to settle
        page.waitForTimeout(2_000.0)
    }

    override fun close() {
        browser?.close()
        browser = null
        playwright?.close()
        playwright = null
    }
}

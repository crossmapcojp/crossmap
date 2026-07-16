package jp.co.crossmap

import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun interface ProcessLauncher {
    fun start(command: List<String>): Process
}

/** Lightweight JavaScript-rendering browser around the Lightpanda CLI. */
class LightPanda(
    private val binary: String = System.getenv("LIGHTPANDA_BINARY")?.takeIf(String::isNotBlank) ?: "lightpanda",
    private val timeout: Duration = Duration.ofSeconds(60),
    private val renderWait: Duration? = null,
    private val maxCapturedBytes: Int = 16 * 1024 * 1024,
    private val launcher: ProcessLauncher = ProcessLauncher { command -> ProcessBuilder(command).start() },
) {
    fun fetchHtml(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "http" || uri.scheme == "https") { "Lightpanda only accepts HTTP(S) URLs: $url" }
        require(timeout.toMillis() > 0) { "Lightpanda timeout must be positive" }
        require(renderWait == null || renderWait.toMillis() > 0) { "Lightpanda render wait must be positive" }
        require(renderWait == null || renderWait < timeout) { "Lightpanda render wait must be shorter than its timeout" }
        require(maxCapturedBytes > 0) { "Lightpanda output limit must be positive" }

        val command = buildList {
            addAll(listOf(binary, "fetch", "--dump", "html"))
            renderWait?.let {
                add("--wait-ms")
                add(it.toMillis().toString())
            }
            add(url)
        }
        val process = launcher.start(command)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val stdout = executor.submit<ByteArray> { process.inputStream.readBounded(maxCapturedBytes) }
            val stderr = executor.submit<ByteArray> { process.errorStream.readBounded(256 * 1024) }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                error("Lightpanda timed out after ${timeout.toSeconds()}s for $url")
            }
            val errorText = stderr.get().toString(Charsets.UTF_8).trim()
            require(process.exitValue() == 0) {
                "Lightpanda exited ${process.exitValue()} for $url${errorText.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            }
            val html = stdout.get().toString(Charsets.UTF_8)
            require(html.isNotBlank()) { "Lightpanda returned an empty page for $url" }
            return html
        } finally {
            executor.shutdownNow()
        }
    }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var captured = 0
        var exceeded = false
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val remaining = limit - captured
            if (remaining > 0) {
                val kept = minOf(read, remaining)
                output.write(buffer, 0, kept)
                captured += kept
                if (kept < read) exceeded = true
            } else {
                exceeded = true
            }
        }
        require(!exceeded) { "Lightpanda output exceeded $limit bytes" }
        return output.toByteArray()
    }
}

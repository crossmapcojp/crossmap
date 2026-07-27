package jp.co.crossmap.crawl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ProxySync(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    fun sync(output: Path): ProxySyncReport {
        val url = "https://raw.githubusercontent.com/iplocate/free-proxy-list/refs/heads/main/protocols/https.txt"
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Failed to fetch proxy list: HTTP ${response.statusCode()}" }
        val lines = response.body().lines().map(String::trim).filter(String::isNotEmpty)
        val proxies = lines.mapNotNull { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                ProxyEntry(parts[0], parts[1])
            } else {
                null
            }
        }
        Files.createDirectories(output.parent)
        val csv = buildString {
            appendLine("ip,port")
            proxies.forEach { appendLine("${it.ip},${it.port}") }
        }
        Files.writeString(output, csv)
        return ProxySyncReport(
            sourceUrl = url,
            proxiesWritten = proxies.size,
        )
    }
}

data class ProxyEntry(val ip: String, val port: String) {
    val address: String get() = "$ip:$port"
}

data class ProxySyncReport(
    val sourceUrl: String,
    val proxiesWritten: Int,
)

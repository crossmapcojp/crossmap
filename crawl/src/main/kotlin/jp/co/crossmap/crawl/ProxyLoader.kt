package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path

class ProxyLoader {
    fun load(proxiesCsv: Path): List<ProxyEntry> {
        if (!Files.isRegularFile(proxiesCsv)) return emptyList()
        val lines = Files.readAllLines(proxiesCsv)
        if (lines.isEmpty()) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val parts = line.split(",", limit = 2).map(String::trim)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                ProxyEntry(parts[0], parts[1])
            } else {
                null
            }
        }
    }
}

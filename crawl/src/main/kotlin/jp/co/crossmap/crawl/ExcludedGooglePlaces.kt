package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path

/** Durable exclusions for Google places verified not to be congregations. */
object ExcludedGooglePlaces {
    fun load(resourcesRoot: Path): Set<String> {
        val file = resourcesRoot.resolve("catalog/excludedGooglePlaces.txt")
        if (!Files.isRegularFile(file)) return emptySet()
        return Files.readAllLines(file).mapNotNull { line ->
            line.substringBefore('#').substringBefore('|').trim().takeIf(String::isNotBlank)
        }.toSet()
    }

    fun contains(excluded: Set<String>, id: String, googleCid: String?): Boolean =
        id in excluded || googleCid in excluded || id.removePrefix("google:") in excluded
}

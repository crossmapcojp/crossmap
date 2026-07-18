package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchWebsitePolicy

object ExcludedChurchListingDomains {
    fun load(resourcesRoot: Path): Set<String> {
        val file = resourcesRoot.resolve("catalog/excludedChurchListingDomains.txt")
        return if (Files.isRegularFile(file)) ChurchWebsitePolicy.parse(Files.readString(file)) else emptySet()
    }

    fun policy(resourcesRoot: Path): ChurchWebsitePolicy = ChurchWebsitePolicy(load(resourcesRoot))
}

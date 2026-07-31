package jp.co.crossmap.crawl

import java.nio.file.Path

/** Explicit boundary between versioned inputs/assets and reproducible machine-local processing state. */
data class CrossmapPaths(
    val resourcesRoot: Path,
    val cacheRoot: Path = defaultCacheRoot(resourcesRoot),
) {
    val googleSavedPlaces: Path get() = cacheRoot.resolve("google-saved-places")
    val googleMapsPages: Path get() = cacheRoot.resolve("web-pages")
    val churchWebPages: Path get() = cacheRoot.resolve("web-pages")
    val webPages: Path get() = cacheRoot.resolve("web-pages")
    val webPagesManual: Path get() = cacheRoot.resolve("web-pages-manual")
    val cloudflareBlockedLog: Path get() = resourcesRoot.resolve("../logs/cloudflare-blocked.log").normalize()
    val cleanup: Path get() = cacheRoot.resolve("cleanup")
    val churchNameTranslation: Path get() = cacheRoot.resolve("church-name-translation")
    val addressNormalization: Path get() = cacheRoot.resolve("address-normalization")
    val normalizedChurchAddresses: Path get() = addressNormalization.resolve("normalized-addresses.json")
    val searchIndexes: Path get() = cacheRoot.resolve("search-indexes/churches")
    val geoNameCache: Path get() = cacheRoot.resolve("geoname")
    val geoNameOfficialJapan: Path get() = geoNameCache.resolve("japan/JP.txt")
    val geoNameOfficialJapanAlternateNames: Path get() = geoNameCache.resolve("japan/alternatenames/JP.txt")
    val geoNameJapanCsv: Path get() = geoNameCache.resolve("japan/geonames.csv")
    val geoNameEnglishLexicon: Path get() = geoNameCache.resolve("japan/church-name-lexicon.json")
    val geoNamesMultilingualLexicon: Path get() = geoNameCache.resolve("japan/geonames-multilingual-lexicon.json")
    val geoNameMultilingualLexicon: Path get() = geoNameCache.resolve("japan/church-name-multilingual-lexicon.json")

    val denominationCatalog: Path get() = resourcesRoot.resolve("catalog/denominations.json")
    val denominationRules: Path get() = resourcesRoot.resolve("cleanup/denomination-rules.json")
    val humanOverrides: Path get() = resourcesRoot.resolve("cleanup/human-overrides.json")
    val geonames: Path get() = resourcesRoot.resolve("geonames/japan.json")
    val churchGeoNameTranslations: Path get() = resourcesRoot.resolve("geonames/church-ja-all.json")
    val jmaCityDictionary: Path get() = resourcesRoot.resolve("geonames/jma-city.json")
    val geoNameDuplicatedChurchNames: Path get() = resourcesRoot.resolve("geonames/geoname-duplicated-church-name.csv")

    companion object {
        fun defaultCacheRoot(resourcesRoot: Path): Path =
            System.getenv("CROSSMAP_CACHE")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: resourcesRoot.toAbsolutePath().normalize().parent.resolve("cache")
    }
}

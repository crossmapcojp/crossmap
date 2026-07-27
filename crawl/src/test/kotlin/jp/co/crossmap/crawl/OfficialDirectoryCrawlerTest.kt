package jp.co.crossmap.crawl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OfficialDirectoryCrawlerTest {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Test
    fun extractsDataDrivenOfficialDirectoryEntriesWithoutDenominationSpecificCode() {
        val root = Files.createTempDirectory("crossmap-directory")
        try {
            Files.createDirectories(root.resolve("sources"))
            Files.createDirectories(root.resolve("cache/cleanup"))
            Files.writeString(
                root.resolve("sources/denominations.json"),
                json.encodeToString(
                    listOf(
                        DenominationDirectorySource(
                            id = "jbc",
                            denominationId = "JBC",
                            denominationName = "日本バプテスト連盟",
                            denominationWebsiteUrl = "https://www.bapren.jp/",
                            churchListUrlList = listOf("https://www.bapren.jp/church/"),
                            entrySelector = ".church",
                            nameSelector = ".name",
                            addressSelector = ".address",
                            urlSelector = "a",
                        )
                    )
                ),
            )
            Files.writeString(root.resolve("cache/cleanup/denomination-candidates.json"), "[]")
            val html = """
                <section class='church'><a class='name' href='/church/hiragishi'>日本バプテスト連盟 平岸バプテスト教会</a><span class='address'>〒062-0934 北海道札幌市豊平区平岸４条２丁目１−１９</span></section>
                <section class='church'><a class='name' href='/church/okayama'>岡山バプテスト教会</a><span class='address'>〒700-0825 岡山県岡山市北区田町１丁目７−２８</span></section>
            """.trimIndent()

            val report = OfficialDirectoryCrawler(DirectoryPageLoader { LoadedDirectoryPage(it, html) }).crawl(root, root.resolve("cache"))

            assertEquals(2, report.candidates)
            val candidates = json.decodeFromString<List<DenominationCandidate>>(Files.readString(root.resolve("cache/cleanup/denomination-candidates.json")))
            assertEquals("〒062-0934 北海道札幌市豊平区平岸４条２丁目１−１９", candidates.first { it.churchName == "日本バプテスト連盟 平岸バプテスト教会" }.address)
            val evidence = json.decodeFromString<List<EvidenceRecord>>(Files.readString(root.resolve("cache/cleanup/denomination-directory-evidence.json")))
            assertEquals(EvidenceKind.DENOMINATION_DIRECTORY, evidence.single { it.name == "岡山バプテスト教会" }.kind)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryLoaderUsesStandaloneCopiedUrlCacheBeforeHttp() {
        val root = Files.createTempDirectory("crossmap-directory-cache")
        try {
            val url = "https://www.bapren.jp/church/"
            val contentHash = "cached-page"
            Files.createDirectories(root.resolve("web-pages/pages"))
            Files.writeString(root.resolve("web-pages/pages/$contentHash.html"), "<p>cached</p>")
            Files.writeString(root.resolve("web-pages/url-cache-map.json"), json.encodeToString(mapOf(url.sha1() to contentHash)))
            var fallbackCalls = 0

            val page = CachedDirectoryPageLoader(root.resolve("web-pages"), DirectoryPageLoader {
                fallbackCalls++
                error("HTTP fallback must not run")
            }).load(url)

            assertTrue(page.html.contains("cached"))
            assertEquals(0, fallbackCalls)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun jurisdictionMayOwnItsChurchListAndInheritDenominationSelectors() {
        val root = Files.createTempDirectory("crossmap-jurisdiction")
        try {
            Files.createDirectories(root.resolve("sources"))
            Files.createDirectories(root.resolve("cache/cleanup"))
            val source = DenominationDirectorySource(
                id = "uccj",
                denominationId = "UCCJ",
                denominationName = "日本基督教団",
                entrySelector = ".church",
                nameSelector = ".name",
                jurisdictionList = listOf(
                    DenominationJurisdictionSource(
                        id = "tokyo-diocese",
                        name = "東京教区",
                        kind = JurisdictionKind.DIOCESE,
                        jurisdictionWebsiteUrl = "https://uccj.org/organization/organization14",
                        churchListUrlList = listOf("https://uccj.org/organization/organization14"),
                    )
                ),
            )
            Files.writeString(root.resolve("sources/denominations.json"), json.encodeToString(listOf(source)))
            Files.writeString(root.resolve("cache/cleanup/denomination-candidates.json"), "[]")

            val report = OfficialDirectoryCrawler(DirectoryPageLoader {
                LoadedDirectoryPage(it, "<div class='church'><span class='name'>日本基督教団 東京教会</span></div>")
            }).crawl(root, root.resolve("cache"))

            assertEquals(1, report.candidates)
            val evidence = json.decodeFromString<List<EvidenceRecord>>(Files.readString(root.resolve("cache/cleanup/denomination-directory-evidence.json"))).single()
            assertEquals("tokyo-diocese", evidence.attributes["jurisdictionId"])
            assertEquals("東京教区", evidence.attributes["jurisdictionName"])
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun genericOfficialListExtractsChurchLinksWithoutCustomSelectors() {
        val root = Files.createTempDirectory("crossmap-generic-directory")
        try {
            Files.createDirectories(root.resolve("sources"))
            Files.createDirectories(root.resolve("cache/cleanup"))
            Files.writeString(
                root.resolve("sources/denominations.json"),
                json.encodeToString(
                    listOf(
                        DenominationDirectorySource(
                            id = "cog-japan",
                            denominationId = "COG_JAPAN",
                            denominationName = "チャーチ・オブ・ゴッド",
                            denominationWebsiteUrl = "https://www.cogjapan.com/",
                            churchListUrlList = listOf("https://www.cogjapan.com/churches"),
                        )
                    )
                ),
            )
            Files.writeString(root.resolve("cache/cleanup/denomination-candidates.json"), "[]")
            val html = "<nav><a href='/churches'>所属教会</a></nav><main><a href='/tokyo'>東京ライトハウスチャーチ</a><a href='/other'>お問い合わせ</a></main>"

            val report = OfficialDirectoryCrawler(DirectoryPageLoader { LoadedDirectoryPage(it, html) }).crawl(root, root.resolve("cache"))

            assertEquals(1, report.candidates)
            val candidate = json.decodeFromString<List<DenominationCandidate>>(Files.readString(root.resolve("cache/cleanup/denomination-candidates.json"))).single()
            assertEquals("東京ライトハウスチャーチ", candidate.churchName)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun excludedListingDirectoryIsRejectedBeforeTheLoaderRuns() {
        val root = Files.createTempDirectory("crossmap-excluded-directory")
        try {
            Files.createDirectories(root.resolve("sources"))
            Files.createDirectories(root.resolve("catalog"))
            Files.writeString(
                root.resolve("sources/denominations.json"),
                json.encodeToString(
                    listOf(
                        DenominationDirectorySource(
                            id = "listing-site",
                            denominationId = "JBC",
                            denominationName = "日本バプテスト連盟",
                            churchListUrlList = listOf("https://search.church-info.jp/churches"),
                        ),
                    ),
                ),
            )
            Files.writeString(root.resolve("catalog/excludedChurchListingDomains.txt"), "church-info.jp\n")
            var loaderCalls = 0

            val report = OfficialDirectoryCrawler(DirectoryPageLoader {
                loaderCalls++
                error("An excluded listing domain must never be loaded")
            }).crawl(root, root.resolve("cache"))

            assertEquals(0, loaderCalls)
            assertEquals(0, report.pages)
            assertEquals(0, report.candidates)
            assertEquals(1, report.excludedUrls)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

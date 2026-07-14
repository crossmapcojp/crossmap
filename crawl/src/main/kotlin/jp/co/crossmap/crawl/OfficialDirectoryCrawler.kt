package jp.co.crossmap.crawl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

@Serializable
enum class JurisdictionKind { DIOCESE, DISTRICT, PARISH, BRANCH, OTHER }

@Serializable
data class DenominationJurisdictionSource(
    val id: String,
    val name: String,
    val kind: JurisdictionKind = JurisdictionKind.OTHER,
    val parentJurisdictionId: String? = null,
    val jurisdictionWebsiteUrl: String = "",
    val churchListUrlList: List<String> = emptyList(),
    val entrySelector: String = "",
    val nameSelector: String = "",
    val addressSelector: String = "",
    val urlSelector: String = "",
)

@Serializable
data class DenominationDirectorySource(
    val id: String,
    val denominationId: String,
    val denominationName: String,
    val denominationWebsiteUrl: String = "",
    val churchListUrlList: List<String> = emptyList(),
    val socialUrlList: List<String> = emptyList(),
    val entrySelector: String = "",
    val nameSelector: String = "",
    val addressSelector: String = "",
    val urlSelector: String = "",
    val sourceSpreadsheetRow: Int? = null,
    val lastVerifiedAt: String = "",
    val jurisdictionList: List<DenominationJurisdictionSource> = emptyList(),
)

data class LoadedDirectoryPage(val url: String, val html: String, val fetchedAt: String = Instant.now().toString())

fun interface DirectoryPageLoader {
    fun load(url: String): LoadedDirectoryPage
}

class HttpDirectoryPageLoader(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : DirectoryPageLoader {
    override fun load(url: String): LoadedDirectoryPage {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", "CrossmapCrawler/1.0 (+https://crossmap.jp)").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} for $url" }
        return LoadedDirectoryPage(response.uri().toString(), response.body())
    }
}

class CachedDirectoryPageLoader(
    private val resourcesRoot: Path,
    private val fallback: DirectoryPageLoader = HttpDirectoryPageLoader(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DirectoryPageLoader {
    override fun load(url: String): LoadedDirectoryPage {
        val mapFile = resourcesRoot.resolve("crawl/url-cache-map.json")
        if (Files.isRegularFile(mapFile)) {
            val cacheMap = json.decodeFromString<Map<String, String>>(Files.readString(mapFile))
            val hash = cacheMap[url.sha1()]
            val page = hash?.let { resourcesRoot.resolve("crawl/pages/$it.html") }
            if (page != null && Files.isRegularFile(page)) return LoadedDirectoryPage(url, Files.readString(page))
        }
        return fallback.load(url)
    }
}

data class DirectoryCrawlReport(val sources: Int, val pages: Int, val candidates: Int, val errors: Int)

class OfficialDirectoryCrawler(
    private val loader: DirectoryPageLoader? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    fun crawl(resourcesRoot: Path): DirectoryCrawlReport {
        val sourceFile = resourcesRoot.resolve("sources/denominations.json")
        require(Files.isRegularFile(sourceFile)) { "Missing standalone denomination source catalog: $sourceFile" }
        val sources = json.decodeFromString<List<DenominationDirectorySource>>(Files.readString(sourceFile))
        val pageLoader = loader ?: CachedDirectoryPageLoader(resourcesRoot, json = json)
        val evidence = mutableListOf<EvidenceRecord>()
        var pages = 0
        var errors = 0
        sources.forEach { source ->
            val targets = listOf(
                DirectoryTarget(
                    sourceId = source.id,
                    jurisdictionId = null,
                    jurisdictionName = null,
                    urls = source.churchListUrlList,
                    entrySelector = source.entrySelector,
                    nameSelector = source.nameSelector,
                    addressSelector = source.addressSelector,
                    urlSelector = source.urlSelector,
                )
            ) + source.jurisdictionList.map { jurisdiction ->
                DirectoryTarget(
                    sourceId = "${source.id}:${jurisdiction.id}",
                    jurisdictionId = jurisdiction.id,
                    jurisdictionName = jurisdiction.name,
                    urls = jurisdiction.churchListUrlList,
                    entrySelector = jurisdiction.entrySelector.ifBlank { source.entrySelector },
                    nameSelector = jurisdiction.nameSelector.ifBlank { source.nameSelector },
                    addressSelector = jurisdiction.addressSelector.ifBlank { source.addressSelector },
                    urlSelector = jurisdiction.urlSelector.ifBlank { source.urlSelector },
                )
            }
            targets.filter { it.urls.isNotEmpty() }.forEach { target ->
            target.urls.forEach { url ->
                runCatching { pageLoader.load(url) }.onFailure { errors++ }.getOrNull()?.let { page ->
                    pages++
                    val document = Jsoup.parse(page.html, page.url)
                    val entries = if (target.entrySelector.isNotBlank() && target.nameSelector.isNotBlank()) {
                        document.select(target.entrySelector).map { entry ->
                            DirectoryEntry(
                                name = entry.select(target.nameSelector).firstOrNull()?.text()?.trim().orEmpty(),
                                address = target.addressSelector.takeIf(String::isNotBlank)
                                    ?.let { entry.select(it).firstOrNull()?.text()?.trim() }.orEmpty(),
                                url = target.urlSelector.takeIf(String::isNotBlank)
                                    ?.let { entry.select(it).firstOrNull()?.absUrl("href") }.orEmpty(),
                                html = entry.html(),
                            )
                        }
                    } else {
                        document.select("a[href]").mapNotNull { anchor ->
                            val name = anchor.text().trim()
                            if (!looksLikeChurchName(name)) null else DirectoryEntry(name, url = anchor.absUrl("href"), html = anchor.outerHtml())
                        }
                    }
                    entries.forEachIndexed { index, entry ->
                        val name = entry.name
                        if (name.isBlank()) return@forEachIndexed
                        val address = entry.address
                        val churchUrl = entry.url
                        val stable = "${target.sourceId}|${page.url}|$index|$name|$address".toByteArray().sha256().take(24)
                        evidence += EvidenceRecord(
                            id = "directory:$stable",
                            kind = EvidenceKind.DENOMINATION_DIRECTORY,
                            entityType = EvidenceEntityType.CHURCH,
                            sourceId = target.sourceId,
                            sourceUrl = page.url,
                            name = name,
                            address = address,
                            attributes = mapOf(
                                "denominationId" to source.denominationId,
                                "denominationName" to source.denominationName,
                                "churchUrl" to churchUrl,
                            ) + listOfNotNull(
                                target.jurisdictionId?.let { "jurisdictionId" to it },
                                target.jurisdictionName?.let { "jurisdictionName" to it },
                            ),
                            fetchedAt = page.fetchedAt,
                            contentHash = entry.html.toByteArray().sha256(),
                        )
                    }
                }
            }
            }
        }
        val sortedEvidence = evidence.distinctBy { it.id }.sortedBy { it.id }
        EvidenceStore(resourcesRoot, json).writeEvidence("evidence/denomination-directory.json", sortedEvidence)
        val candidateFile = resourcesRoot.resolve("cleanup/denomination-candidates.json")
        val existing = if (Files.isRegularFile(candidateFile)) {
            json.decodeFromString<List<DenominationCandidate>>(Files.readString(candidateFile))
        } else emptyList()
        val discovered = sortedEvidence.map {
            DenominationCandidate(
                denominationId = it.attributes.getValue("denominationId"),
                churchName = it.name,
                address = it.address,
                url = it.attributes["churchUrl"].orEmpty(),
                source = it.sourceUrl,
            )
        }
        Files.createDirectories(candidateFile.parent)
        val part = candidateFile.resolveSibling("${candidateFile.fileName}.part")
        Files.writeString(part, json.encodeToString((existing + discovered).distinctBy { Triple(it.denominationId, it.churchName, it.address) }.sortedWith(compareBy({ it.denominationId }, { it.churchName }))))
        runCatching { Files.move(part, candidateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, candidateFile, StandardCopyOption.REPLACE_EXISTING) }
        return DirectoryCrawlReport(sources.size, pages, discovered.size, errors)
    }
}

private data class DirectoryEntry(val name: String, val address: String = "", val url: String = "", val html: String)

private fun looksLikeChurchName(value: String): Boolean {
    val compact = value.replace(Regex("\\s+"), "")
    if (compact.length !in 3..100) return false
    if (compact in setOf("教会一覧", "教会検索", "教会紹介", "所属教会", "加盟教会一覧")) return false
    return listOf("教会", "チャーチ", "チャペル", "伝道所", "礼拝堂", "キリスト集会", "聖堂").any(compact::contains)
}

private data class DirectoryTarget(
    val sourceId: String,
    val jurisdictionId: String?,
    val jurisdictionName: String?,
    val urls: List<String>,
    val entrySelector: String,
    val nameSelector: String,
    val addressSelector: String,
    val urlSelector: String,
)

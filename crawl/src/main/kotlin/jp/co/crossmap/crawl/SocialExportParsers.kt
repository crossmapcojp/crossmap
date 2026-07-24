package jp.co.crossmap.crawl

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import jp.co.crossmap.SocialPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

data class SocialExportInputPaths(
    val youtubeSubscribedChannelsCsv: Path?,
    val instagramFollowingJson: Path?,
    val facebookFollowingRawHtml: Path?,
    val facebookFollowingJson: Path?,
    val twitterListMembersJson: Path?,
)

object SocialExportInputs {
    private const val YOUTUBE = "crossmap.youtubeSubscribedChannelsCsv"
    private const val INSTAGRAM = "crossmap.instagramFollowingJson"
    private const val FACEBOOK_HTML = "crossmap.facebookFollowingRawHtml"
    private const val FACEBOOK_JSON = "crossmap.facebookFollowingJson"
    private const val TWITTER = "crossmap.twitterListMembersJson"

    fun load(start: Path = Path.of("").toAbsolutePath().normalize()): SocialExportInputPaths {
        val propertiesFile = generateSequence(start) { it.parent }
            .map { it.resolve("local.properties") }
            .firstOrNull(Files::isRegularFile)
            ?: error("local.properties was not found from $start")
        val properties = Properties().apply { Files.newInputStream(propertiesFile).use(::load) }
        fun path(key: String): Path? {
            val configured = properties.getProperty(key)?.trim().orEmpty()
            if (configured.isBlank()) return null
            val raw = Path.of(configured)
            val candidates = if (raw.isAbsolute) listOf(raw) else listOf(
                propertiesFile.parent.resolve(raw),
                Path.of(System.getProperty("user.home"), "Downloads").resolve(raw),
            )
            return candidates.firstOrNull(Files::isRegularFile)
                ?: error("$key does not resolve to a file: $configured")
        }
        return SocialExportInputPaths(path(YOUTUBE), path(INSTAGRAM), path(FACEBOOK_HTML), path(FACEBOOK_JSON), path(TWITTER))
    }
}

interface SocialAccountExportParser {
    fun parse(path: Path): List<SocialAccountCandidate>
}

class YouTubeSubscribedChannelsCsvParser : SocialAccountExportParser {
    override fun parse(path: Path): List<SocialAccountCandidate> {
        val rows = parseCsv(Files.readString(path)).filter { row -> row.any(String::isNotBlank) }
        require(rows.isNotEmpty()) { "YouTube export is empty: $path" }
        val header = rows.first().map { it.removePrefix("\uFEFF").trim() }
        fun column(vararg names: String): Int = header.indexOfFirst { value -> names.any(value::equals) }
            .takeIf { it >= 0 } ?: error("YouTube export is missing ${names.joinToString()}: $header")
        val id = column("チャンネル ID", "Channel Id", "Channel ID")
        val url = column("チャンネルの URL", "Channel Url", "Channel URL")
        val title = column("チャンネルのタイトル", "Channel Title")
        return rows.drop(1).mapNotNull { row ->
            val channelId = row.getOrNull(id)?.trim().orEmpty()
            val channelUrl = row.getOrNull(url)?.trim().orEmpty()
            val channelTitle = row.getOrNull(title)?.trim().orEmpty()
            if (channelId.isBlank() || channelUrl.isBlank() || channelTitle.isBlank()) null else SocialAccountCandidate(
                id = "youtube:$channelId",
                platform = SocialPlatform.YOUTUBE,
                url = SocialUrlNormalizer.canonical(channelUrl, SocialPlatform.YOUTUBE),
                accountName = channelTitle,
                sourceUrl = path.toUri().toString(),
            )
        }.distinctBy(SocialAccountCandidate::id)
    }
}

class InstagramFollowingJsonParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SocialAccountExportParser {
    override fun parse(path: Path): List<SocialAccountCandidate> {
        val root = json.parseToJsonElement(Files.readString(path)).jsonObject
        return root["relationships_following"].orEmptyArray().mapNotNull { element ->
            val entry = element.jsonObject
            val name = entry.string("title")
            val data = entry["string_list_data"].orEmptyArray().firstOrNull()?.jsonObject
            val url = data?.string("href").orEmpty()
            if (name.isBlank() || url.isBlank()) null else SocialAccountCandidate(
                id = "instagram:${SocialUrlNormalizer.handle(url)}",
                platform = SocialPlatform.INSTAGRAM,
                url = SocialUrlNormalizer.canonical(url, SocialPlatform.INSTAGRAM),
                accountName = name,
                sourceUrl = path.toUri().toString(),
            )
        }.distinctBy(SocialAccountCandidate::id)
    }
}

interface FacebookChurchPageParser : SocialAccountExportParser

class FacebookChurchPageHtmlParser : FacebookChurchPageParser {
    override fun parse(path: Path): List<SocialAccountCandidate> {
        val document = Jsoup.parse(path.toFile(), "UTF-8", "https://www.facebook.com/")
        return document.select("a[href]").mapNotNull { link ->
            val url = SocialUrlNormalizer.unwrapFacebookRedirect(link.absUrl("href").ifBlank { link.attr("href") })
            val canonical = SocialUrlNormalizer.canonical(url, SocialPlatform.FACEBOOK)
            val name = link.text().trim()
            canonical.takeIf { SocialUrlNormalizer.isFacebookProfile(it) }?.let { it to name }
        }.groupBy { it.first }
            .mapNotNull { (url, values) ->
                val name = values.asSequence().map(Pair<String, String>::second)
                    .filter(String::isNotBlank)
                    .filterNot(::isFacebookNavigationText)
                    .maxByOrNull(String::length)
                    ?: return@mapNotNull null
                SocialAccountCandidate(
                    id = "facebook:${SocialUrlNormalizer.handle(url)}",
                    platform = SocialPlatform.FACEBOOK,
                    url = url,
                    accountName = name,
                    sourceUrl = path.toUri().toString(),
                )
            }.distinctBy(SocialAccountCandidate::id)
    }

    private fun isFacebookNavigationText(value: String): Boolean = value in setOf(
        "友達", "フォロー中", "共通の友達", "Friends", "Following", "See all",
    ) || Regex("^(?:共通の友達|Mutual friends)\\s*\\d+").containsMatchIn(value)
}

/** Reserved for Facebook's pending downloadable JSON format. */
class FacebookChurchPageJsonParser : FacebookChurchPageParser {
    override fun parse(path: Path): List<SocialAccountCandidate> = emptyList()
}

class TwitterListMembersJsonParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SocialAccountExportParser {
    override fun parse(path: Path): List<SocialAccountCandidate> = json.parseToJsonElement(Files.readString(path))
        .jsonArray.mapNotNull { element ->
            val entry = element.jsonObject
            val legacy = entry["metadata"]?.jsonObject?.get("legacy") as? JsonObject
            val screenName = entry.string("screen_name").ifBlank { legacy?.string("screen_name").orEmpty() }
            val name = entry.string("name").ifBlank { legacy?.string("name").orEmpty() }
            val description = entry.string("description").ifBlank { legacy?.string("description").orEmpty() }
            if (screenName.isBlank() || name.isBlank()) null else SocialAccountCandidate(
                id = "x:$screenName",
                platform = SocialPlatform.X,
                url = "https://x.com/$screenName",
                accountName = name,
                description = description,
                sourceUrl = path.toUri().toString(),
            )
        }.distinctBy(SocialAccountCandidate::id)
}

object SocialUrlNormalizer {
    private val socialHosts = mapOf(
        "facebook.com" to SocialPlatform.FACEBOOK,
        "instagram.com" to SocialPlatform.INSTAGRAM,
        "x.com" to SocialPlatform.X,
        "twitter.com" to SocialPlatform.X,
        "youtube.com" to SocialPlatform.YOUTUBE,
        "youtu.be" to SocialPlatform.YOUTUBE,
    )

    fun platform(url: String): SocialPlatform? = runCatching {
        val host = URI(url.trim()).host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
        socialHosts.entries.firstOrNull { (domain) -> host == domain || host.endsWith(".$domain") }?.value
    }.getOrNull()

    fun canonical(url: String, platform: SocialPlatform? = platform(url)): String {
        if (url.isBlank()) return ""
        val unwrapped = if (platform == SocialPlatform.FACEBOOK) unwrapFacebookRedirect(url) else url
        return runCatching {
            val uri = URI(unwrapped.trim().replace("http://", "https://"))
            var host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching unwrapped.trim()
            if (platform == SocialPlatform.FACEBOOK && host.endsWith(".facebook.com")) host = "facebook.com"
            var path = uri.path.orEmpty().replace(Regex("/+"), "/").trimEnd('/')
            if (platform == SocialPlatform.INSTAGRAM) path = path.removePrefix("/_u")
            var facebookProfileId: String? = null
            if (platform == SocialPlatform.FACEBOOK) {
                path = path.removePrefix("/pg")
                val peopleSegments = path.trim('/').split('/')
                if (peopleSegments.firstOrNull() == "people" && peopleSegments.size >= 3) {
                    facebookProfileId = peopleSegments.last()
                    path = "/profile.php"
                } else {
                    path = path.replace(Regex("/(?:about|videos|photos|posts)$", RegexOption.IGNORE_CASE), "")
                }
            }
            if (platform == SocialPlatform.YOUTUBE) {
                path = path.replace(
                    Regex("^(/(?:channel/[^/]+|@[^/]+))/(?:featured|videos|shorts|streams|about)$", RegexOption.IGNORE_CASE),
                    "$1",
                )
            }
            val query = if (platform == SocialPlatform.FACEBOOK && path == "/profile.php") {
                facebookProfileId?.let { "?id=$it" }
                    ?: uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("id=") }?.let { "?$it" }.orEmpty()
            } else ""
            "https://$host$path$query"
                .replace("https://twitter.com/", "https://x.com/")
                .replace("https://m.facebook.com/", "https://facebook.com/")
        }.getOrElse { unwrapped.trim().substringBefore('#').substringBefore('?').trimEnd('/') }
    }

    fun identityKey(url: String, platform: SocialPlatform? = platform(url)): String {
        val canonical = canonical(url, platform)
        return when (platform) {
            SocialPlatform.FACEBOOK,
            SocialPlatform.INSTAGRAM,
            SocialPlatform.X,
            -> canonical.lowercase()
            else -> canonical
        }
    }

    fun handle(url: String): String = runCatching {
        val uri = URI(canonical(url))
        if (uri.path == "/profile.php") uri.rawQuery?.substringAfter("id=")?.substringBefore('&').orEmpty()
        else uri.path.orEmpty().trim('/').substringAfterLast('/').ifBlank { uri.host.orEmpty() }
    }.getOrDefault(url.substringAfterLast('/'))

    fun unwrapFacebookRedirect(url: String): String = runCatching {
        val uri = URI(url.replace("&amp;", "&"))
        if (uri.host?.lowercase()?.endsWith("facebook.com") == true && uri.path == "/l.php") {
            uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("u=") }
                ?.substringAfter("u=")?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) } ?: url
        } else url
    }.getOrDefault(url)

    fun isFacebookProfile(url: String): Boolean {
        if (platform(url) != SocialPlatform.FACEBOOK) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank() || path == "profile.php" && !uri.rawQuery.orEmpty().contains("id=")) return false
        val first = path.substringBefore('/').lowercase()
        if (first in setOf("groups", "events", "marketplace", "watch", "gaming", "help", "settings", "hokuto.ide")) return false
        return path.substringAfter('/', "") !in setOf("friends", "friends_mutual", "followers", "following")
    }
}

class SocialExportReader {
    fun read(inputs: SocialExportInputPaths): List<SocialAccountCandidate> = buildList {
        inputs.youtubeSubscribedChannelsCsv?.let { addAll(YouTubeSubscribedChannelsCsvParser().parse(it)) }
        inputs.instagramFollowingJson?.let { addAll(InstagramFollowingJsonParser().parse(it)) }
        inputs.facebookFollowingRawHtml?.let { addAll(FacebookChurchPageHtmlParser().parse(it)) }
        inputs.facebookFollowingJson?.let { addAll(FacebookChurchPageJsonParser().parse(it)) }
        inputs.twitterListMembersJson?.let { addAll(TwitterListMembersJsonParser().parse(it)) }
    }.distinctBy { it.platform to SocialUrlNormalizer.canonical(it.url, it.platform) }
}

private fun parseCsv(value: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '"' && quoted && value.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> { row += cell.toString(); cell.clear() }
            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && value.getOrNull(index + 1) == '\n') index++
                row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
            }
            else -> cell.append(char)
        }
        index++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
    return rows
}

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
private fun JsonElement?.orEmptyArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

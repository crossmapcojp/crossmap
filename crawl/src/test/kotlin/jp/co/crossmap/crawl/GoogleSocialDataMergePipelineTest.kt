package jp.co.crossmap.crawl

import java.nio.file.Files
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoPoint
import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GoogleSocialDataMergePipelineTest {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun decomposedNamesCoverOfficialPlatformAndDenominationDecorations() {
        val cases = listOf(
            "日本基督教団 東京教会" to "東京教会 公式チャンネル",
            "日本聖公会東京聖アンデレ教会" to "東京聖アンデレ教会公式",
            "宗教法人 日本バプテスト柏教会" to "日本バプテスト柏教会 YouTube Channel",
            "御坊はこぶね教会" to "御坊はこぶね教会／礼拝配信",
            "尾張旭福音自由教会" to "尾張旭福音自由教会【公式】",
            "土山みことばキリスト教会" to "土山みことばキリスト教会",
        )
        cases.forEachIndexed { index, (churchName, accountName) ->
            val matcher = SocialChurchAccountMatcher(listOf(church("google:$index", churchName)))
            val decision = matcher.match(account("youtube:$index", SocialPlatform.YOUTUBE, accountName, "https://youtube.com/channel/UC$index"))
            val expected = if (index < 2) SocialMergeStatus.ESTIMATED_MATCH else SocialMergeStatus.EXACT_MATCH
            assertEquals(expected, decision.status, "$churchName <> $accountName")
        }
    }

    @Test
    fun irregularNameOrderCanBeEstimatedButAmbiguousAndNonChurchAccountsAreNotPublished() {
        val matcher = SocialChurchAccountMatcher(
            listOf(
                church("google:1", "カトリック築地教会"),
                church("google:2", "築地聖路加教会"),
                church("google:3", "東京希望教会"),
                church("google:4", "大阪希望教会"),
            ),
        )

        val reordered = matcher.match(account("instagram:tsukiji", SocialPlatform.INSTAGRAM, "築地カトリック教会", "https://instagram.com/tsukiji_catholic"))
        val ambiguous = matcher.match(account("x:kibo", SocialPlatform.X, "希望教会", "https://x.com/kibo_church"))
        val person = matcher.match(account("youtube:person", SocialPlatform.YOUTUBE, "上原ヨシュア", "https://youtube.com/channel/person"))
        val foreign = matcher.match(account("facebook:foreign", SocialPlatform.FACEBOOK, "AD Belém Church", "https://facebook.com/adbelem"))
        val school = matcher.match(account("facebook:school", SocialPlatform.FACEBOOK, "小林聖心女子学院", "https://facebook.com/school"))

        assertEquals(SocialMergeStatus.EXACT_MATCH, reordered.status)
        assertEquals(SocialMergeStatus.NOT_MATCHED, ambiguous.status)
        assertEquals(SocialMergeStatus.EXCLUDED, person.status)
        assertEquals(SocialMergeStatus.EXCLUDED, foreign.status)
        assertEquals(SocialMergeStatus.EXCLUDED, school.status)
    }

    @Test
    fun explicitConflictingDenominationsNeverMatchOnlyBecauseTheCongregationNameMatches() {
        val matcher = SocialChurchAccountMatcher(
            listOf(church("google:1", "日本キリスト教団新潟教会")),
        )

        val decision = matcher.match(
            account("facebook:niigata", SocialPlatform.FACEBOOK, "カトリック新潟教会", "https://facebook.com/niigata.catholic"),
        )

        assertEquals(SocialMergeStatus.NOT_MATCHED, decision.status)
    }

    @Test
    fun pipelineMigratesGoogleSocialWebsiteAndWritesHumanReadableNonExactAudit() {
        val root = Files.createTempDirectory("crossmap-google-social")
        val resources = root.resolve("resources")
        Files.createDirectories(resources.resolve("catalog"))
        val google = church("google:1", "東京教会").copy(websiteUrl = "https://www.facebook.com/tokyo.church/")
        Files.writeString(resources.resolve("catalog/churches.json"), json.encodeToString(listOf(google)))
        val youtube = root.resolve("youtube.csv")
        Files.writeString(
            youtube,
            "チャンネル ID,チャンネルの URL,チャンネルのタイトル\n" +
                "UC1,https://youtube.com/channel/UC1,東京教会\n" +
                "UC2,https://youtube.com/channel/UC2,個人の日記\n",
        )
        val audit = root.resolve("logs/2026-07-23-19-04-google-social-data-merge.log")

        val report = GoogleSocialDataMergePipeline(json).run(
            resourcesRoot = resources,
            inputs = SocialExportInputPaths(youtube, null, null, null, null),
            applyChanges = true,
            auditLog = audit,
        )

        assertEquals(1, report.socialWebsiteUrlsMigrated)
        assertEquals(1, report.exactMatches)
        assertEquals(1, report.excluded)
        val updated = json.decodeFromString<List<ChurchRecord>>(Files.readString(resources.resolve("catalog/churches.json"))).single()
        assertTrue(updated.websiteUrl.isBlank())
        assertTrue(Files.readString(resources.resolve("catalog/churches.json")).contains("\"websiteUrl\": null"))
        assertEquals(setOf(SocialPlatform.FACEBOOK, SocialPlatform.YOUTUBE), updated.socialProfiles.map { it.platform }.toSet())
        val log = Files.readString(audit)
        assertTrue(log.startsWith("google social data merge summary"))
        assertTrue(log.contains("performed operation: excluded"))
        assertTrue(log.contains("account name: 個人の日記"))
        assertTrue(Files.isRegularFile(resources.resolve("evidence/social-accounts.json")))
    }

    private fun church(id: String, name: String) = ChurchRecord(
        id = id,
        name = name,
        englishName = "$name Church",
        address = "〒100-0001 東京都千代田区千代田１−１",
        location = GeoPoint(35.0, 139.0),
        websiteUrl = "https://$id.example/",
    )

    private fun account(id: String, platform: SocialPlatform, name: String, url: String) = SocialAccountCandidate(
        id = id,
        platform = platform,
        url = url,
        accountName = name,
    )
}

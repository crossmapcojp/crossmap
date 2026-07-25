package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import jp.co.crossmap.SocialPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocialExportParsersTest {
    @Test
    fun youtubeCsvSupportsJapaneseHeadersQuotedCommasAndEmbeddedNewlines() {
        val file = Files.createTempFile("youtube-following", ".csv")
        Files.writeString(
            file,
            "\uFEFFチャンネル ID,チャンネルの URL,チャンネルのタイトル\r\n" +
                "UC1,http://www.youtube.com/channel/UC1,東京教会\r\n" +
                "UC2,https://youtube.com/channel/UC2,\"Grace Church, Tokyo\"\r\n" +
                "UC3,https://youtube.com/channel/UC3,\"改行を含む\n教会\"\r\n",
        )

        val accounts = YouTubeSubscribedChannelsCsvParser().parse(file)

        assertEquals(3, accounts.size)
        assertEquals("Grace Church, Tokyo", accounts[1].accountName)
        assertEquals("改行を含む\n教会", accounts[2].accountName)
        assertEquals("https://youtube.com/channel/UC1", accounts[0].url)
    }

    @Test
    fun instagramParserUsesTitleAndCanonicalizesPrivateUPath() {
        val file = Files.createTempFile("instagram-following", ".json")
        Files.writeString(
            file,
            """{"relationships_following":[{"title":"tokyo_church","string_list_data":[{"href":"https://www.instagram.com/_u/tokyo_church","timestamp":1}]}]}""",
        )

        val account = InstagramFollowingJsonParser().parse(file).single()

        assertEquals("instagram:tokyo_church", account.id)
        assertEquals("https://instagram.com/tokyo_church", account.url)
        assertEquals("tokyo_church", account.accountName)
    }

    @Test
    fun facebookHtmlDeduplicatesImageAndTextAnchorsAndRejectsNavigationProfiles() {
        val file = Files.createTempFile("facebook-following", ".html")
        Files.writeString(
            file,
            """
            <a href="https://www.facebook.com/tokyo.church"><img alt=""></a>
            <a href="https://www.facebook.com/tokyo.church">東京キリスト教会</a>
            <a href="https://www.facebook.com/tokyo.church/friends_mutual">共通の友達46人</a>
            <a href="https://www.facebook.com/hokuto.ide/friends">友達</a>
            <a href="https://www.facebook.com/profile.php?id=61500000000001">恵み教会</a>
            """.trimIndent(),
        )

        val accounts = FacebookChurchPageHtmlParser().parse(file)

        assertEquals(2, accounts.size)
        assertEquals("東京キリスト教会", accounts.single { "tokyo.church" in it.url }.accountName)
        assertEquals("https://facebook.com/profile.php?id=61500000000001", accounts.single { "profile.php" in it.url }.url)
    }

    @Test
    fun facebookJsonParserReadsNameOnlyRowsAndRepairsMetaMojibake() {
        val file = Files.createTempFile("facebook-following", ".json")
        val mojibake = "日本キリスト教団 東京教会".toByteArray(StandardCharsets.UTF_8)
            .toString(StandardCharsets.ISO_8859_1)
        Files.writeString(
            file,
            """{"pages_followed_v2":[
              {"timestamp":1,"data":[{"name":"$mojibake"}],"title":"$mojibake"},
              {"timestamp":2,"data":[{"name":"Grace Church Tokyo"}],"title":"Grace Church Tokyo"}
            ]}""",
        )

        val accounts = FacebookChurchPageJsonParser().parse(file)

        assertEquals(2, accounts.size)
        assertEquals("日本キリスト教団 東京教会", accounts[0].accountName)
        assertEquals("", accounts[0].url)
        assertTrue(accounts[0].id.startsWith("facebook-export-name:"))
        assertEquals("Grace Church Tokyo", accounts[1].accountName)
    }

    @Test
    fun facebookJsonNameOnlyRowsAreNotCollapsedByTheCombinedReader() {
        val file = Files.createTempFile("facebook-following", ".json")
        Files.writeString(
            file,
            """{"pages_followed_v2":[
              {"data":[{"name":"東京教会"}],"title":"東京教会"},
              {"data":[{"name":"大阪教会"}],"title":"大阪教会"}
            ]}""",
        )

        val accounts = SocialExportReader().read(SocialExportInputPaths(null, null, null, file, null))

        assertEquals(listOf("東京教会", "大阪教会"), accounts.map { it.accountName })
    }

    @Test
    fun twitterParserAcceptsCurrentTopLevelAndLegacyMetadataFields() {
        val file = Files.createTempFile("twitter-list", ".json")
        Files.writeString(
            file,
            """[
              {"id":"1","screen_name":"tokyo_church","name":"東京教会","description":"礼拝案内","metadata":{}},
              {"id":"2","metadata":{"legacy":{"screen_name":"osaka_church","name":"大阪教会","description":"Church in Osaka"}}}
            ]""".trimIndent(),
        )

        val accounts = TwitterListMembersJsonParser().parse(file)

        assertEquals(2, accounts.size)
        assertEquals(SocialPlatform.X, accounts[1].platform)
        assertEquals("https://x.com/osaka_church", accounts[1].url)
        assertEquals("Church in Osaka", accounts[1].description)
    }

    @Test
    fun socialUrlNormalizationRecognizesAllWebsitePlatforms() {
        assertEquals(SocialPlatform.FACEBOOK, SocialUrlNormalizer.platform("https://m.facebook.com/test"))
        assertEquals(SocialPlatform.INSTAGRAM, SocialUrlNormalizer.platform("https://www.instagram.com/test/"))
        assertEquals(SocialPlatform.X, SocialUrlNormalizer.platform("https://twitter.com/test"))
        assertEquals(SocialPlatform.YOUTUBE, SocialUrlNormalizer.platform("https://youtu.be/video"))
        assertEquals("https://x.com/test", SocialUrlNormalizer.canonical("http://www.twitter.com/test/", SocialPlatform.X))
        assertEquals(
            SocialUrlNormalizer.identityKey("https://facebook.com/FukuokaYayoiChurch", SocialPlatform.FACEBOOK),
            SocialUrlNormalizer.identityKey("https://facebook.com/pg/FukuokaYayoiChurch", SocialPlatform.FACEBOOK),
        )
        assertEquals(
            SocialUrlNormalizer.identityKey("https://facebook.com/nagaokacovenant", SocialPlatform.FACEBOOK),
            SocialUrlNormalizer.identityKey("https://ja-jp.facebook.com/nagaokacovenant", SocialPlatform.FACEBOOK),
        )
        assertEquals(
            SocialUrlNormalizer.identityKey("https://facebook.com/profile.php?id=100068760264839", SocialPlatform.FACEBOOK),
            SocialUrlNormalizer.identityKey(
                "https://facebook.com/people/日本福音ルーテル岐阜教会/100068760264839",
                SocialPlatform.FACEBOOK,
            ),
        )
        assertEquals(
            "https://youtube.com/channel/UCQagVG78BWgieXWr4TNgr9w",
            SocialUrlNormalizer.canonical(
                "https://youtube.com/channel/UCQagVG78BWgieXWr4TNgr9w/featured",
                SocialPlatform.YOUTUBE,
            ),
        )
    }
}

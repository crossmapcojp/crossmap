package jp.co.crossmap.crawl

import java.nio.file.Files
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
    fun facebookJsonParserIsExplicitlyNoOpUntilExportArrives() {
        val file = Files.createTempFile("facebook-following", ".json")
        Files.writeString(file, "{\"unknown_future_format\":[]}")
        assertTrue(FacebookChurchPageJsonParser().parse(file).isEmpty())
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
    }
}

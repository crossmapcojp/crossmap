package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalizedStaticSiteGeneratorTest {
    private val projectRoot = Path.of(requireNotNull(System.getProperty("crossmap.project.root")))
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun generatesFiveNoJavaScriptChurchPagesWithReciprocalSeoLinks() {
        val church = json.decodeFromString<List<ChurchRecord>>(
            Files.readString(projectRoot.resolve("resources/catalog/churches.json")),
        ).single { it.id == "google:6646597370070891755" }
        val denominationCatalogs = Language.entries.associate { language ->
            language.code to json.decodeFromString<Map<String, String>>(
                Files.readString(projectRoot.resolve("resources/catalog/denomination-${language.code}-names.json")),
            )
        }
        val output = Files.createTempDirectory("crossmap-localized-site")
        val generated = LocalizedStaticSiteGenerator(
            XmlMessageCatalog.load(projectRoot.resolve("resources/i18n")),
            "https://churches.example",
        ).generate(
            churches = listOf(church),
            denominationEnglishNames = denominationCatalogs.getValue("en"),
            denominationNamesByLanguage = denominationCatalogs,
            outputDirectory = output,
        )

        assertEquals(Language.entries.size, generated.churchPages.size)
        assertEquals(1, generated.churchPages.map(LocalizedGeneratedChurchPage::slug).distinct().size)
        val slug = generated.churchPages.single { it.language == Language.ENGLISH }.slug
        Language.entries.forEach { language ->
            val page = output.resolve(language.code).resolve("$slug.html")
            assertTrue(Files.isRegularFile(page))
            val html = Files.readString(page)
            assertTrue(html.contains("<html lang=\"${language.code}\">"))
            assertTrue(html.contains("rel=\"canonical\" href=\"https://churches.example/${language.code}/$slug.html\""))
            Language.entries.forEach { alternate ->
                assertTrue(html.contains("hreflang=\"${alternate.code}\" href=\"https://churches.example/${alternate.code}/$slug.html\""))
            }
            assertTrue(html.contains("hreflang=\"x-default\" href=\"https://churches.example/en/$slug.html\""))
            assertTrue(html.contains(church.address))
            assertTrue(html.contains(church.websiteUrl))
            assertFalse(html.contains("id=\"localized-name-options\""))
            assertFalse(html.contains("navigator.language"))
            assertTrue(html.contains("href=\"../${Language.JAPANESE.code}/$slug.html\""))
            val jsonLdText = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: error("JSON-LD missing")
            val jsonLd = json.parseToJsonElement(jsonLdText) as JsonObject
            assertEquals("Church", jsonLd["@type"]?.toString()?.trim('"'))
        }
        assertTrue(Files.isRegularFile(output.resolve("ja/index.html")))
        assertTrue(Files.isRegularFile(output.resolve("en/result.html")))
        assertTrue(Files.readString(output.resolve("sitemap.xml")).contains("xhtml:link"))
    }

    @Test
    fun rendersIndependentClassificationAboveAddressInEveryLanguage() {
        val church = json.decodeFromString<List<ChurchRecord>>(
            Files.readString(projectRoot.resolve("resources/catalog/churches.json")),
        ).single { it.id == "google:10049608052870801463" }
        assertEquals("INDEPENDENT_CHURCH", church.denominationId)
        val denominationCatalogs = Language.entries.associate { language ->
            language.code to json.decodeFromString<Map<String, String>>(
                Files.readString(projectRoot.resolve("resources/catalog/denomination-${language.code}-names.json")),
            )
        }
        val expected = mapOf(
            Language.JAPANESE to "単立",
            Language.ENGLISH to "Independent",
            Language.KOREAN to "독립 교회",
            Language.PORTUGUESE to "Independente",
            Language.INDONESIAN to "Independen",
        )
        val output = Files.createTempDirectory("crossmap-independent-site")
        try {
            val generated = LocalizedStaticSiteGenerator(
                XmlMessageCatalog.load(projectRoot.resolve("resources/i18n")),
                "https://churches.example",
            ).generate(
                churches = listOf(church),
                denominationEnglishNames = denominationCatalogs.getValue("en"),
                denominationNamesByLanguage = denominationCatalogs,
                outputDirectory = output,
            )
            generated.churchPages.forEach { page ->
                val html = Files.readString(page.path)
                val article = html.substringAfter("<article").substringBefore("</article>")
                val classification = expected.getValue(page.language)
                assertTrue(article.contains(classification), page.language.code)
                assertTrue(article.indexOf(classification) < article.indexOf(church.address), page.language.code)
                assertFalse(html.contains("INDEPENDENT_CHURCH"), page.language.code)
            }
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}

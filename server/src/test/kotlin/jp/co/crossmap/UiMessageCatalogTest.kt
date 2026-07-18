package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class UiMessageCatalogTest {
    private val projectRoot = Path.of(requireNotNull(System.getProperty("crossmap.project.root")))

    @Test
    fun canonicalCatalogCoversExactlyEveryLanguageAndMessageKey() {
        val catalog = XmlMessageCatalog.load(projectRoot.resolve("resources/i18n"))
        Language.entries.forEach { language ->
            MessageKey.entries.forEach { key ->
                val argumentCount = when (key) {
                    MessageKey.SEARCH_RESULTS_NEARBY_TITLE -> 2
                    in formattedKeys -> 1
                    else -> 0
                }
                val arguments = Array<Any>(argumentCount) { "東京バプテスト教会" }
                catalog.text(language, key, *arguments)
            }
        }
        assertEquals("Search results for “Tokyo Baptist Church”", catalog.text(
            Language.ENGLISH,
            MessageKey.SEARCH_RESULTS_TITLE,
            "Tokyo Baptist Church",
        ))
        assertEquals("「東京バプテスト教会」の検索結果", catalog.text(
            Language.JAPANESE,
            MessageKey.SEARCH_RESULTS_TITLE,
            "東京バプテスト教会",
        ))
        assertEquals("伊豆市付近の「日本基督教団」の検索結果", catalog.text(
            Language.JAPANESE,
            MessageKey.SEARCH_RESULTS_NEARBY_TITLE,
            "伊豆市",
            "日本基督教団",
        ))
    }

    @Test
    fun parserRejectsDoctypeBeforeResolvingExternalEntities() {
        val root = copyCanonicalCatalog()
        root.resolve("values/strings.xml").writeText(
            """<!DOCTYPE resources [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><resources><string name="site_name">&xxe;</string></resources>""",
        )
        assertFails { XmlMessageCatalog.load(root) }
    }

    @Test
    fun parserRejectsDuplicateBlankAndPlaceholderMismatch() {
        val duplicate = copyCanonicalCatalog()
        val english = duplicate.resolve("values/strings.xml")
        english.writeText(english.toFile().readText().replace(
            "</resources>",
            "<string name=\"site_name\">Duplicate</string></resources>",
        ))
        assertFails { XmlMessageCatalog.load(duplicate) }

        val blank = copyCanonicalCatalog()
        val japanese = blank.resolve("values-ja/strings.xml")
        japanese.writeText(japanese.toFile().readText().replace(
            "<string name=\"site_name\">Crossmap 教会検索</string>",
            "<string name=\"site_name\"></string>",
        ))
        assertFails { XmlMessageCatalog.load(blank) }

        val mismatch = copyCanonicalCatalog()
        val korean = mismatch.resolve("values-ko/strings.xml")
        korean.writeText(korean.toFile().readText().replace(
            "“%1\$s” 검색 결과",
            "“검색어” 검색 결과",
        ))
        assertFails { XmlMessageCatalog.load(mismatch) }
    }

    @Test
    fun formatterRejectsMissingArguments() {
        val catalog = XmlMessageCatalog.load(projectRoot.resolve("resources/i18n"))
        assertFailsWith<IllegalArgumentException> {
            catalog.text(Language.ENGLISH, MessageKey.CHURCH_PAGE_TITLE)
        }
    }

    private fun copyCanonicalCatalog(): Path {
        val destination = Files.createTempDirectory("crossmap-i18n-test")
        Files.walk(projectRoot.resolve("resources/i18n")).use { paths ->
            paths.forEach { source ->
                val relative = projectRoot.resolve("resources/i18n").relativize(source)
                val target = destination.resolve(relative.toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        return destination
    }

    private val formattedKeys = setOf(
        MessageKey.SEARCH_RESULTS_TITLE,
        MessageKey.SEARCH_RESULTS_COUNT,
        MessageKey.DISTANCE_KM,
        MessageKey.CHURCH_PAGE_TITLE,
        MessageKey.CHURCH_PAGE_DESCRIPTION,
    )
}

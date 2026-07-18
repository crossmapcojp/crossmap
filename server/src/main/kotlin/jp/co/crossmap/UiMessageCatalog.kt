package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

enum class MessageKey(val xmlName: String) {
    SITE_NAME("site_name"),
    SEARCH_HEADING("search_heading"),
    SEARCH_PLACEHOLDER("search_placeholder"),
    SEARCH_BUTTON("search_button"),
    SEARCH_RESULTS_TITLE("search_results_title"),
    SEARCH_RESULTS_COUNT("search_results_count"),
    NO_RESULTS("no_results"),
    LOADING("loading"),
    BACK_TO_SEARCH("back_to_search"),
    PREVIOUS_PAGE("previous_page"),
    NEXT_PAGE("next_page"),
    LANGUAGE_PICKER("language_picker"),
    LANGUAGE_PICKER_ARIA("language_picker_aria"),
    CHURCH_LABEL("church_label"),
    CHURCH_ADDRESS("church_address"),
    CHURCH_DENOMINATION("church_denomination"),
    CHURCH_WEBSITE("church_website"),
    CHURCH_SOCIAL_LINKS("church_social_links"),
    DOWNLOAD_INDEX_PROMPT("download_index_prompt"),
    DOWNLOAD_INDEX_BUTTON("download_index_button"),
    DOWNLOAD_FAILED("download_failed"),
    INDEX_UNAVAILABLE("index_unavailable"),
    SEARCH_FAILED("search_failed"),
    CHURCH_DETAIL_UNAVAILABLE("church_detail_unavailable"),
    DISTANCE_KM("distance_km"),
    HOME_TITLE("home_title"),
    HOME_DESCRIPTION("home_description"),
    RESULTS_TITLE("results_title"),
    RESULTS_DESCRIPTION("results_description"),
    CHURCH_PAGE_TITLE("church_page_title"),
    CHURCH_PAGE_DESCRIPTION("church_page_description"),
    CHOOSE_LANGUAGE("choose_language"),
    SERVER_ERROR("server_error"),
    ;
}

interface MessageCatalog {
    fun text(language: Language, key: MessageKey, vararg arguments: Any): String
}

class XmlMessageCatalog private constructor(
    private val messages: Map<Language, Map<MessageKey, String>>,
) : MessageCatalog {
    override fun text(language: Language, key: MessageKey, vararg arguments: Any): String {
        val template = messages.getValue(language).getValue(key)
        val expected = placeholderIndexes(template)
        require(arguments.size == (expected.maxOrNull() ?: 0)) {
            "${key.xmlName} expects ${expected.maxOrNull() ?: 0} argument(s), got ${arguments.size}"
        }
        return PLACEHOLDER.replace(template) { match ->
            arguments[match.groupValues[1].toInt() - 1].toString()
        }
    }

    companion object {
        private val PLACEHOLDER = Regex("%([1-9]\\d*)\\\$s")
        private val ANY_PERCENT = Regex("%(?![1-9]\\d*\\\$s|%)")

        fun load(root: Path): XmlMessageCatalog {
            require(Files.isDirectory(root)) { "Missing i18n directory: $root" }
            val expectedDirectories = Language.entries.associateWith(::directoryName)
            val actualDirectories = Files.list(root).use { paths ->
                paths.filter(Files::isDirectory).map { it.fileName.toString() }.toList().toSet()
            }
            require(actualDirectories == expectedDirectories.values.toSet()) {
                "i18n directories must be exactly ${expectedDirectories.values.sorted()}; found=${actualDirectories.sorted()}"
            }

            val raw = expectedDirectories.mapValues { (_, directory) ->
                parse(root.resolve(directory).resolve("strings.xml"))
            }
            val expectedKeys = MessageKey.entries.map(MessageKey::xmlName).toSet()
            raw.forEach { (language, values) ->
                require(values.keys == expectedKeys) {
                    "${language.code} keys differ; missing=${expectedKeys - values.keys}, unexpected=${values.keys - expectedKeys}"
                }
                values.forEach { (key, value) ->
                    require(value.isNotBlank()) { "${language.code}/$key must not be blank" }
                    require(!ANY_PERCENT.containsMatchIn(value)) { "${language.code}/$key has an unsupported placeholder" }
                }
            }
            val english = raw.getValue(Language.ENGLISH)
            raw.forEach { (language, values) ->
                expectedKeys.forEach { key ->
                    require(placeholderIndexes(values.getValue(key)) == placeholderIndexes(english.getValue(key))) {
                        "Placeholder mismatch for ${language.code}/$key"
                    }
                }
            }
            return XmlMessageCatalog(
                raw.mapValues { (_, values) ->
                    MessageKey.entries.associateWith { values.getValue(it.xmlName) }
                },
            )
        }

        private fun directoryName(language: Language): String =
            if (language == Language.ENGLISH) "values" else "values-${language.code}"

        private fun parse(path: Path): Map<String, String> {
            require(Files.isRegularFile(path)) { "Missing message catalog: $path" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isExpandEntityReferences = false
                isXIncludeAware = false
            }
            val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
            require(document.documentElement.tagName == "resources") { "Root element must be <resources>: $path" }
            val result = linkedMapOf<String, String>()
            val nodes = document.documentElement.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                require(node.nodeName == "string") { "Unexpected <${node.nodeName}> in $path" }
                val name = node.attributes.getNamedItem("name")?.nodeValue?.trim().orEmpty()
                require(name.isNotBlank()) { "String name must not be blank in $path" }
                require(result.put(name, node.textContent) == null) { "Duplicate key '$name' in $path" }
            }
            return result
        }

        private fun placeholderIndexes(value: String): Set<Int> = PLACEHOLDER.findAll(value)
            .map { it.groupValues[1].toInt() }
            .toSet()
    }
}

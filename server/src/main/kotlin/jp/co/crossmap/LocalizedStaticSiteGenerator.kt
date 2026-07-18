package jp.co.crossmap

import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

data class LocalizedGeneratedChurchPage(
    val churchId: String,
    val language: Language,
    val slug: String,
    val path: Path,
    val pageUrl: String,
    val canonicalUrl: String,
)

data class LocalizedSiteResult(
    val churchPages: List<LocalizedGeneratedChurchPage>,
    val localizedPageUrls: Map<String, Map<String, String>>,
    val parallelism: Int,
    val workerThreadsUsed: Int,
    val generationDurationMillis: Long,
)

class LocalizedStaticSiteGenerator(
    private val messages: MessageCatalog,
    private val siteBaseUrl: String,
    private val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
) {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val logger = LoggerFactory.getLogger(LocalizedStaticSiteGenerator::class.java)
    private val baseUrl = siteBaseUrl.trimEnd('/').also {
        require(it.startsWith("https://") || it.startsWith("http://")) { "siteBaseUrl must be absolute" }
    }
    private val json = Json { encodeDefaults = true }
    private val freemarker = Configuration(Configuration.VERSION_2_3_34).apply {
        defaultEncoding = StandardCharsets.UTF_8.name()
        templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        logTemplateExceptions = false
        wrapUncheckedExceptions = true
        localizedLookup = false
        setClassForTemplateLoading(LocalizedStaticSiteGenerator::class.java, "/")
    }

    fun generate(
        churches: List<ChurchRecord>,
        denominationEnglishNames: Map<String, String>,
        denominationNamesByLanguage: Map<String, Map<String, String>>,
        outputDirectory: Path,
        collisionLocationEnglishNames: Map<String, String> = emptyMap(),
        excludedChurchListingDomains: Set<String> = emptySet(),
    ): LocalizedSiteResult {
        require(denominationNamesByLanguage.keys == Language.entries.map(Language::code).toSet()) {
            "Denomination catalogs must map exactly every Language"
        }
        val slugger = StaticSiteGenerator()
        val baseSlugs = churches.associateWith { church ->
            slugger.pageSlug(
                church.denominationId?.takeIf(String::isDisplayableDenominationId)?.let(denominationEnglishNames::get),
                church.englishName,
            )
        }
        val collisions = baseSlugs.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        val slugs = baseSlugs.mapValues { (church, baseSlug) ->
            if (baseSlug !in collisions) baseSlug else {
                val location = requireNotNull(collisionLocationEnglishNames[church.id]) {
                    "Missing English collision location for ${church.id} (${church.name})"
                }
                slugger.pageSlug(
                    church.denominationId?.takeIf(String::isDisplayableDenominationId)?.let(denominationEnglishNames::get),
                    "$location ${church.englishName}",
                )
            }
        }
        require(slugs.values.groupingBy(String::lowercase).eachCount().none { it.value > 1 }) {
            "Localized static pages require stable unique English slugs"
        }

        Files.createDirectories(outputDirectory)
        val indexTemplate = freemarker.getTemplate("index.html")
        val resultTemplate = freemarker.getTemplate("result.html")
        val churchTemplate = freemarker.getTemplate("church.html")
        val websitePolicy = ChurchWebsitePolicy(excludedChurchListingDomains)

        val sortedChurches = churches.sortedBy(ChurchRecord::id)
        Language.entries.forEach { language ->
            val languageDirectory = outputDirectory.resolve(language.code)
            Files.createDirectories(languageDirectory)
            val expectedHtml = churches.map { "${slugs.getValue(it)}.html" }.toSet() + setOf("index.html", "result.html")
            removeStaleHtml(languageDirectory, expectedHtml)
        }
        val jobs = buildList<Callable<LocalizedGeneratedChurchPage?>> {
            Language.entries.forEach { language ->
                val languageDirectory = outputDirectory.resolve(language.code)
                add(Callable {
                    writeTemplate(
                        languageDirectory.resolve("index.html"),
                        indexTemplate,
                        shellModel(language, "index.html", rootEntry = false),
                    )
                    null
                })
                add(Callable {
                    writeTemplate(
                        languageDirectory.resolve("result.html"),
                        resultTemplate,
                        resultModel(language),
                    )
                    null
                })
                sortedChurches.forEach { church ->
                    val slug = slugs.getValue(church)
                    val fileName = "$slug.html"
                    val pageUrl = "/${language.code}/$fileName"
                    val canonicalUrl = absolute(pageUrl)
                    val destination = languageDirectory.resolve(fileName)
                    add(Callable {
                        writeTemplate(
                            destination,
                            churchTemplate,
                            churchModel(
                                church,
                                language,
                                slug,
                                canonicalUrl,
                                denominationNamesByLanguage,
                                websitePolicy,
                            ),
                        )
                        LocalizedGeneratedChurchPage(church.id, language, slug, destination, pageUrl, canonicalUrl)
                    })
                }
            }
            add(Callable {
                writeTemplate(
                    outputDirectory.resolve("index.html"),
                    indexTemplate,
                    shellModel(Language.ENGLISH, "index.html", rootEntry = true),
                )
                null
            })
            add(Callable {
                writeAtomically(outputDirectory.resolve("sitemap.xml"), sitemap(slugs))
                null
            })
        }
        val workerThreads = ConcurrentHashMap.newKeySet<String>()
        val executor = Executors.newFixedThreadPool(parallelism) { task ->
            Thread(task, "crossmap-static-site-${staticSiteThreadSequence.incrementAndGet()}").apply { isDaemon = true }
        }
        val startedAt = System.nanoTime()
        val pages = try {
            executor.invokeAll(jobs.map { job ->
                Callable {
                    workerThreads += Thread.currentThread().name
                    job.call()
                }
            }).mapNotNull { it.get() }
        } finally {
            executor.shutdown()
        }
        val generationDurationMillis = (System.nanoTime() - startedAt) / 1_000_000
        logger.info(
            "Generated {} localized HTML files with parallelism={} workersUsed={} durationMs={}",
            jobs.size,
            parallelism,
            workerThreads.size,
            generationDurationMillis,
        )
        return LocalizedSiteResult(
            churchPages = pages,
            localizedPageUrls = pages.groupBy(LocalizedGeneratedChurchPage::churchId).mapValues { (_, variants) ->
                variants.associate { it.language.code to it.pageUrl }
            },
            parallelism = parallelism,
            workerThreadsUsed = workerThreads.size,
            generationDurationMillis = generationDurationMillis,
        )
    }

    private fun shellModel(language: Language, fileName: String, rootEntry: Boolean): Map<String, Any> {
        val path = if (rootEntry) "/" else "/${language.code}/$fileName"
        return mapOf(
            "rootEntry" to rootEntry,
            "languageCode" to language.code,
            "title" to messages.text(language, MessageKey.HOME_TITLE),
            "description" to messages.text(language, MessageKey.HOME_DESCRIPTION),
            "canonicalUrl" to absolute(path),
            "xDefaultUrl" to absolute("/"),
            "alternates" to alternateModels(fileName),
            "ogLocale" to ogLocale(language),
            "siteName" to messages.text(language, MessageKey.SITE_NAME),
            "heading" to messages.text(language, if (rootEntry) MessageKey.CHOOSE_LANGUAGE else MessageKey.SEARCH_HEADING),
            "languagePickerAria" to messages.text(language, MessageKey.LANGUAGE_PICKER_ARIA),
            "languageLinks" to languageLinks(fileName, rootEntry),
            "searchPlaceholder" to messages.text(language, MessageKey.SEARCH_PLACEHOLDER),
            "searchButton" to messages.text(language, MessageKey.SEARCH_BUTTON),
        )
    }

    private fun resultModel(language: Language): Map<String, Any> = mapOf(
        "languageCode" to language.code,
        "title" to messages.text(language, MessageKey.RESULTS_TITLE),
        "description" to messages.text(language, MessageKey.RESULTS_DESCRIPTION),
        "canonicalUrl" to absolute("/${language.code}/result.html"),
        "xDefaultUrl" to absolute("/"),
        "alternates" to alternateModels("result.html"),
        "siteName" to messages.text(language, MessageKey.SITE_NAME),
        "languagePickerAria" to messages.text(language, MessageKey.LANGUAGE_PICKER_ARIA),
        "languageLinks" to languageLinks("result.html", false),
        "backToSearch" to messages.text(language, MessageKey.BACK_TO_SEARCH),
        "resultsTitle" to messages.text(language, MessageKey.RESULTS_TITLE),
        "loading" to messages.text(language, MessageKey.LOADING),
        "pageMessagesJson" to safeJson(
            mapOf(
                "searchResultsTitle" to messages.text(language, MessageKey.SEARCH_RESULTS_TITLE, "{query}"),
                "searchResultsNearbyTitle" to messages.text(
                    language,
                    MessageKey.SEARCH_RESULTS_NEARBY_TITLE,
                    "{location}",
                    "{query}",
                ),
                "searchResultsCount" to messages.text(language, MessageKey.SEARCH_RESULTS_COUNT, "{count}"),
                "noResults" to messages.text(language, MessageKey.NO_RESULTS),
                "loading" to messages.text(language, MessageKey.LOADING),
                "previousPage" to messages.text(language, MessageKey.PREVIOUS_PAGE),
                "nextPage" to messages.text(language, MessageKey.NEXT_PAGE),
                "distanceKm" to messages.text(language, MessageKey.DISTANCE_KM, "{distance}"),
                "serverError" to messages.text(language, MessageKey.SERVER_ERROR),
                "indexUnavailable" to messages.text(language, MessageKey.INDEX_UNAVAILABLE),
            ),
        ),
    )

    private fun churchModel(
        church: ChurchRecord,
        language: Language,
        slug: String,
        canonicalUrl: String,
        denominationNamesByLanguage: Map<String, Map<String, String>>,
        websitePolicy: ChurchWebsitePolicy,
    ): Map<String, Any> {
        val localizedNames = church.localizedNames + listOf(
            LocalizedName(Language.JAPANESE.code, church.name),
            LocalizedName(Language.ENGLISH.code, church.englishName),
        )
        val churchName = requireNotNull(localizedDomainText(language, localizedNames, church.englishName, church.name))
        val alternateNames = localizedNames.filter { Language.fromCode(it.languageCode) == language }
            .map(LocalizedName::name).filter(String::isNotBlank).distinct().filterNot { it == churchName }
        val isIndependent = church.denominationId == "INDEPENDENT_CHURCH"
        val denominationId = church.denominationId?.takeIf(String::isDisplayableDenominationId)
        val denominationValues = denominationId?.let { id ->
            Language.entries.mapNotNull { target ->
                denominationNamesByLanguage[target.code]?.get(id)?.let { LocalizedName(target.code, it) }
            }
        }.orEmpty()
        val denominationName = if (isIndependent) {
            messages.text(language, MessageKey.CHURCH_INDEPENDENT)
        } else {
            localizedDomainText(
                language,
                denominationValues,
                denominationNamesByLanguage[Language.ENGLISH.code]?.get(denominationId),
                denominationNamesByLanguage[Language.JAPANESE.code]?.get(denominationId),
            ).orEmpty()
        }
        val website = websitePolicy.publicWebsiteUrl(church)
        val pageFile = "$slug.html"
        val languageLinks = languageLinks(pageFile, false)
        val description = messages.text(language, MessageKey.CHURCH_PAGE_DESCRIPTION, churchName)
        val socialProfiles = church.socialProfiles.map {
            mapOf(
                "platform" to it.platform.name,
                "url" to it.url,
                "label" to listOfNotNull(it.displayName, it.handle).firstOrNull().orEmpty(),
            )
        }
        return mapOf(
            "languageCode" to language.code,
            "title" to messages.text(language, MessageKey.CHURCH_PAGE_TITLE, churchName),
            "description" to description,
            "canonicalUrl" to canonicalUrl,
            "xDefaultUrl" to absolute("/en/$pageFile"),
            "alternates" to alternateModels(pageFile),
            "ogLocale" to ogLocale(language),
            "backToSearch" to messages.text(language, MessageKey.BACK_TO_SEARCH),
            "languagePickerAria" to messages.text(language, MessageKey.LANGUAGE_PICKER_ARIA),
            "languageLinks" to languageLinks,
            "churchLabel" to messages.text(language, MessageKey.CHURCH_LABEL),
            "churchName" to churchName,
            "alternateNames" to alternateNames,
            "denominationLabel" to messages.text(language, MessageKey.CHURCH_DENOMINATION),
            "denominationName" to denominationName,
            "addressLabel" to messages.text(language, MessageKey.CHURCH_ADDRESS),
            "address" to church.address,
            "websiteLabel" to messages.text(language, MessageKey.CHURCH_WEBSITE),
            "websiteUrl" to website,
            "socialLinksLabel" to messages.text(language, MessageKey.CHURCH_SOCIAL_LINKS),
            "socialProfiles" to socialProfiles,
            "jsonLd" to jsonLd(church, churchName, alternateNames, website, canonicalUrl),
        )
    }

    private fun jsonLd(
        church: ChurchRecord,
        churchName: String,
        alternateNames: List<String>,
        website: String,
        canonicalUrl: String,
    ): String = safeJson(buildJsonObject {
        put("@context", "https://schema.org")
        put("@type", "Church")
        put("@id", canonicalUrl)
        put("name", churchName)
        if (alternateNames.isNotEmpty()) put("alternateName", buildJsonArray { alternateNames.forEach { add(it) } })
        put("address", church.address)
        put("geo", buildJsonObject {
            put("@type", "GeoCoordinates")
            put("latitude", church.location.latitude)
            put("longitude", church.location.longitude)
        })
        put("url", website)
        if (church.socialProfiles.isNotEmpty()) put("sameAs", buildJsonArray {
            church.socialProfiles.map(SocialProfile::url).distinct().forEach { add(it) }
        })
    })

    private fun languageLinks(fileName: String, rootEntry: Boolean): List<Map<String, String>> = Language.entries.map {
        mapOf(
            "languageCode" to it.code,
            "label" to it.displayName,
            "url" to if (rootEntry) "${it.code}/index.html" else "../${it.code}/$fileName",
        )
    }

    private fun alternateModels(fileName: String): List<Map<String, String>> = Language.entries.map {
        mapOf("languageCode" to it.code, "url" to absolute("/${it.code}/$fileName"))
    }

    private fun sitemap(slugs: Map<ChurchRecord, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n")
        val files = listOf("index.html", "result.html") + slugs.values.sorted().map { "$it.html" }
        Language.entries.forEach { language ->
            files.forEach { file ->
                append("  <url><loc>${xml(absolute("/${language.code}/$file"))}</loc>")
                Language.entries.forEach { alternate ->
                    append("<xhtml:link rel=\"alternate\" hreflang=\"${alternate.code}\" href=\"${xml(absolute("/${alternate.code}/$file"))}\"/>")
                }
                append("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"${xml(absolute("/en/$file"))}\"/></url>\n")
            }
        }
        append("</urlset>\n")
    }

    private fun removeStaleHtml(directory: Path, expected: Set<String>) {
        Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".html") && it.fileName.toString() !in expected }
                .forEach(Files::delete)
        }
    }

    private fun writeTemplate(destination: Path, template: freemarker.template.Template, model: Map<String, Any>) {
        val html = StringWriter().use { writer -> template.process(model, writer); writer.toString() }
        writeAtomically(destination, html)
    }

    private fun writeAtomically(destination: Path, content: String) {
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}", ".tmp")
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        runCatching {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun absolute(path: String): String = "$baseUrl/${path.trimStart('/')}"
    private fun ogLocale(language: Language): String = when (language) {
        Language.JAPANESE -> "ja_JP"
        Language.ENGLISH -> "en_US"
        Language.KOREAN -> "ko_KR"
        Language.PORTUGUESE -> "pt_BR"
        Language.INDONESIAN -> "id_ID"
    }
    private fun safeJson(value: Any): String = when (value) {
        is kotlinx.serialization.json.JsonElement -> json.encodeToString(value)
        is Map<*, *> -> json.encodeToString(value.entries.associate { it.key.toString() to it.value.toString() })
        else -> json.encodeToString(value.toString())
    }.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    private companion object {
        val staticSiteThreadSequence = java.util.concurrent.atomic.AtomicInteger()
    }
}

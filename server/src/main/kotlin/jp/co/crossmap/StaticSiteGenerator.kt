package jp.co.crossmap

import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer

data class GeneratedChurchPage(
    val churchId: String,
    val fileName: String,
    val path: Path,
    val pageUrl: String,
    val canonicalUrl: String,
)

class StaticSiteGenerator(
    private val templateName: String = "church.html",
) {
    private val freemarker = Configuration(Configuration.VERSION_2_3_34).apply {
        defaultEncoding = StandardCharsets.UTF_8.name()
        templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        logTemplateExceptions = false
        wrapUncheckedExceptions = true
        localizedLookup = false
        setClassForTemplateLoading(StaticSiteGenerator::class.java, "/")
    }

    fun generate(
        churches: List<ChurchRecord>,
        denominationEnglishNames: Map<String, String>,
        outputDirectory: Path,
        collisionLocationEnglishNames: Map<String, String> = emptyMap(),
    ): List<GeneratedChurchPage> {
        val missingEnglishNames = churches.filter { it.englishName.isNullOrBlank() }
        require(missingEnglishNames.isEmpty()) {
            "Every church needs englishName before static publication; missing=${missingEnglishNames.size}: " +
                missingEnglishNames.take(10).joinToString { "${it.id} (${it.name})" }
        }
        val missingDenominations = churches.mapNotNull { it.denominationId }
            .filterNot(::isIndependentDenomination)
            .distinct()
            .filter { denominationEnglishNames[it].isNullOrBlank() }
        require(missingDenominations.isEmpty()) {
            "Missing English denomination names: ${missingDenominations.joinToString()}"
        }

        val baseSlugs = churches.associateWith { church ->
            pageSlug(
                denominationEnglishName = church.denominationId
                    ?.takeUnless(::isIndependentDenomination)
                    ?.let(denominationEnglishNames::get),
                churchEnglishName = church.englishName,
            )
        }
        val collisions = baseSlugs.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        val finalSlugs = baseSlugs.mapValues { (church, baseSlug) ->
            if (baseSlug !in collisions) return@mapValues baseSlug
            val location = collisionLocationEnglishNames[church.id]
            require(!location.isNullOrBlank()) {
                "English-name collision for '$baseSlug'; provide an English city/address prefix for ${church.id} (${church.name})"
            }
            pageSlug(
                denominationEnglishName = church.denominationId
                    ?.takeUnless(::isIndependentDenomination)
                    ?.let(denominationEnglishNames::get),
                churchEnglishName = "$location ${church.englishName}",
            )
        }
        val repeatedFinalSlugs = finalSlugs.values.groupingBy(String::lowercase).eachCount().filterValues { it > 1 }
        require(repeatedFinalSlugs.isEmpty()) {
            "English city/address prefixes did not resolve URL collisions: ${repeatedFinalSlugs.keys.joinToString()}"
        }

        Files.createDirectories(outputDirectory)
        val template = freemarker.getTemplate(templateName)
        return churches.sortedBy { it.id }.map { church ->
            val fileName = "${requireNotNull(finalSlugs[church])}.html"
            val pageUrl = "/church/$fileName"
            val canonicalUrl = pageUrl
            val model = mapOf(
                "japaneseName" to church.name,
                "englishName" to church.englishName,
                "denominationEnglishName" to (
                    church.denominationId
                        ?.takeUnless(::isIndependentDenomination)
                        ?.let(denominationEnglishNames::get)
                        ?: ""
                    ),
                "address" to church.address,
                "websiteUrl" to church.websiteUrl,
                "socialProfiles" to church.socialProfiles.map {
                    mapOf(
                        "platform" to it.platform.name,
                        "url" to it.url,
                        "label" to listOfNotNull(it.displayName, it.handle).firstOrNull().orEmpty(),
                    )
                },
                "canonicalUrl" to canonicalUrl,
                "pageUrl" to pageUrl,
            )
            val html = StringWriter().use { writer ->
                template.process(model, writer)
                writer.toString()
            }
            val destination = outputDirectory.resolve(fileName)
            writeAtomically(destination, html)
            GeneratedChurchPage(church.id, fileName, destination, pageUrl, canonicalUrl)
        }
    }

    internal fun pageSlug(denominationEnglishName: String?, churchEnglishName: String): String =
        listOfNotNull(denominationEnglishName?.takeIf(String::isNotBlank), churchEnglishName)
            .joinToString("-")
            .toUrlSlug()
            .also { require(it.isNotBlank()) { "English names did not produce a URL slug" } }

    private fun String.toUrlSlug(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""['’]"""), "")
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun writeAtomically(destination: Path, html: String) {
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}", ".tmp")
        Files.writeString(temporary, html, StandardCharsets.UTF_8)
        runCatching {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isIndependentDenomination(id: String): Boolean =
        id.isBlank() || id == "NOT_DETERMINED" || id == "INDEPENDENT_CHURCH"
}

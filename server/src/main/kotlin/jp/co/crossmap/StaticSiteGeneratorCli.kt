package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Command-line entry point used by the Gradle generateChurchPages task. */
object StaticSiteGeneratorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 6..7) {
            "Usage: <churches.json> <denomination-en-names.json> <output-directory> <geoname-english-lexicon.json> <i18n-directory> <site-base-url> [parallelism]"
        }
        val catalog = Path.of(args[0])
        val denominationNamesFile = Path.of(args[1])
        val output = Path.of(args[2])
        val geonameLexiconFile = Path.of(args[3])
        val i18nDirectory = Path.of(args[4])
        val siteBaseUrl = args[5]
        val parallelism = args.getOrNull(6)?.toInt()
            ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        val catalogBytes = Files.readAllBytes(catalog)
        val churches = json.decodeFromString<List<ChurchRecord>>(catalogBytes.toString(Charsets.UTF_8))
        val denominationEnglishNames = json.decodeFromString<Map<String, String>>(Files.readString(denominationNamesFile))
        val denominationNamesByLanguage = supportedLanguageCodes.associateWith { languageCode ->
            val file = denominationNamesFile.parent.resolve("denomination-$languageCode-names.json")
            require(Files.isRegularFile(file)) { "Missing denomination name catalog: $file" }
            json.decodeFromString<Map<String, String>>(Files.readString(file))
        }
        val geonameEnglishLexicon = json.decodeFromString<Map<String, String>>(Files.readString(geonameLexiconFile))
        val excludedDomainsFile = catalog.parent.resolve("excludedChurchListingDomains.txt")
        val excludedDomains = if (Files.isRegularFile(excludedDomainsFile)) {
            ChurchWebsitePolicy.parse(Files.readString(excludedDomainsFile))
        } else {
            emptySet()
        }
        val slugger = StaticSiteGenerator()
        val collisionLocations = ChurchPageCollisionResolver.resolve(
            churches,
            denominationEnglishNames,
            geonameEnglishLexicon,
            slugger,
        )
        val generated = LocalizedStaticSiteGenerator(
            messages = XmlMessageCatalog.load(i18nDirectory),
            siteBaseUrl = siteBaseUrl,
            parallelism = parallelism,
        ).generate(
            churches = churches,
            denominationEnglishNames = denominationEnglishNames,
            denominationNamesByLanguage = denominationNamesByLanguage,
            outputDirectory = output,
            collisionLocationEnglishNames = collisionLocations,
            excludedChurchListingDomains = excludedDomains,
        )
        val manifest = ChurchPageManifest(
            sourceSha256 = catalogBytes.sha256(),
            pages = generated.localizedPageUrls.mapValues { (_, variants) -> variants.getValue(Language.ENGLISH.code) },
            localizedPages = generated.localizedPageUrls,
        )
        val temporary = Files.createTempFile(output, ".church-page-manifest-", ".json")
        Files.writeString(temporary, json.encodeToString(manifest))
        Files.move(
            temporary,
            output.resolve("manifest.json"),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        println(
            "Generated ${generated.churchPages.size} localized church pages in $output: " +
                "parallelism=${generated.parallelism}, workersUsed=${generated.workerThreadsUsed}, " +
                "durationMs=${generated.generationDurationMillis}",
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }
}

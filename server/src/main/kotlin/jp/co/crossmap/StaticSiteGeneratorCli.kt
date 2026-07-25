package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jp.co.crossmap.catalog.neo4j.CatalogSchemaMigrator
import jp.co.crossmap.catalog.neo4j.Neo4jConfig
import jp.co.crossmap.catalog.neo4j.Neo4jDriverManager
import jp.co.crossmap.catalog.neo4j.Neo4jGraphTransactionRunner
import jp.co.crossmap.catalog.neo4j.Neo4jStaticChurchCatalogSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Command-line entry point used by the Gradle generateChurchPages task. */
object StaticSiteGeneratorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 5..6) {
            "Usage: <denomination-en-names.json> <output-directory> <geoname-english-lexicon.json> <i18n-directory> <site-base-url> [parallelism]"
        }
        val denominationNamesFile = Path.of(args[0])
        val output = Path.of(args[1])
        val geonameLexiconFile = Path.of(args[2])
        val i18nDirectory = Path.of(args[3])
        val siteBaseUrl = args[4]
        val parallelism = args.getOrNull(5)?.toInt()
            ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        val snapshot = runBlocking {
            Neo4jDriverManager(Neo4jConfig.fromEnvironmentAndLocalProperties()).use { manager ->
                manager.verifyConnectivity()
                val health = manager.health(CatalogSchemaMigrator.EXPECTED_VERSION)
                check(health.schemaVersion == CatalogSchemaMigrator.EXPECTED_VERSION && health.catalogImported) {
                    "Neo4j catalog is not ready for static generation: $health"
                }
                Neo4jStaticChurchCatalogSource(
                    Neo4jGraphTransactionRunner(manager.driver, manager.config.database),
                ).read()
            }
        }
        val churches = snapshot.churches
        val denominationEnglishNames = json.decodeFromString<Map<String, String>>(Files.readString(denominationNamesFile))
        val denominationNamesByLanguage = supportedLanguageCodes.associateWith { languageCode ->
            val file = denominationNamesFile.parent.resolve("denomination-$languageCode-names.json")
            require(Files.isRegularFile(file)) { "Missing denomination name catalog: $file" }
            json.decodeFromString<Map<String, String>>(Files.readString(file))
        }
        val geonameEnglishLexicon = json.decodeFromString<Map<String, String>>(Files.readString(geonameLexiconFile))
        val excludedDomainsFile = denominationNamesFile.parent.resolve("excludedChurchListingDomains.txt")
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
            sourceSha256 = snapshot.sourceChecksum,
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

}

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
        require(args.size == 4) {
            "Usage: <churches.json> <denomination-en-names.json> <output-directory> <geoname-english-lexicon.json>"
        }
        val catalog = Path.of(args[0])
        val denominationNamesFile = Path.of(args[1])
        val output = Path.of(args[2])
        val geonameLexiconFile = Path.of(args[3])
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
        val generator = StaticSiteGenerator()
        val pages = generator.generate(
            churches = churches,
            denominationEnglishNames = denominationEnglishNames,
            outputDirectory = output,
            collisionLocationEnglishNames = ChurchPageCollisionResolver.resolve(
                churches,
                denominationEnglishNames,
                geonameEnglishLexicon,
                generator,
            ),
            denominationNamesByLanguage = denominationNamesByLanguage,
            excludedChurchListingDomains = excludedDomains,
        )
        val manifest = ChurchPageManifest(
            sourceSha256 = catalogBytes.sha256(),
            pages = pages.associate { it.churchId to it.pageUrl },
        )
        val temporary = Files.createTempFile(output, ".church-page-manifest-", ".json")
        Files.writeString(temporary, json.encodeToString(manifest))
        Files.move(
            temporary,
            output.resolve("manifest.json"),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        println("Generated ${pages.size} church pages in $output")
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }
}

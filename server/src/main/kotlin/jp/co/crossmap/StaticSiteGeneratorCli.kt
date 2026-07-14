package jp.co.crossmap

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Command-line entry point used by the Gradle generateChurchPages task. */
object StaticSiteGeneratorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) {
            "Usage: <churches.json> <denomination-english-names.json> <output-directory>"
        }
        val catalog = Path.of(args[0])
        val denominationNamesFile = Path.of(args[1])
        val output = Path.of(args[2])
        val json = Json { ignoreUnknownKeys = true }
        val churches = json.decodeFromString<List<ChurchRecord>>(Files.readString(catalog))
        val denominationEnglishNames = json.decodeFromString<Map<String, String>>(Files.readString(denominationNamesFile))
        val pages = StaticSiteGenerator().generate(
            churches = churches,
            denominationEnglishNames = denominationEnglishNames,
            outputDirectory = output,
        )
        println("Generated ${pages.size} church pages in $output")
    }
}

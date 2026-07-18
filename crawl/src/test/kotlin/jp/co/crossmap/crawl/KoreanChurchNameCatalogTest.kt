package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoreanChurchNameCatalogTest {
    private val resources = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        .resolve("resources")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun everyKoreanChurchNameContainsNoLatinExceptAnEvidencedThreeOrFourLetterAbbreviation() {
        val churches = json.decodeFromString<List<ChurchRecord>>(
            Files.readString(resources.resolve("catalog/churches.json")),
        )
        val latinToken = Regex("""[A-Za-z]+""")

        churches.forEach { church ->
            val allowed = JapaneseRomajiToHangul.churchAbbreviations(church.name)
            church.localizedNames
                .filter { it.languageCode.substringBefore('-').lowercase() == "ko" }
                .forEach { localized ->
                    val unexpected = latinToken.findAll(localized.name)
                        .map(MatchResult::value)
                        .filterNot(allowed::contains)
                        .toList()
                    assertTrue(unexpected.isEmpty(), "${church.name}: ${localized.name}; unexpected=$unexpected allowed=$allowed")
                }
        }
    }

    @Test
    fun preservesRealAbbreviationButTranslatesPortugueseWords() {
        val hcc = JapaneseRomajiToHangul.transliterateLatinFragments(
            "HCC Live Church Tsuyama",
            JapaneseRomajiToHangul.churchAbbreviations("HCCライブチャーチ津山"),
        )
        assertTrue(hcc.startsWith("HCC"))
        assertTrue(hcc.removePrefix("HCC").none { it in 'A'..'Z' || it in 'a'..'z' })

        val godIsLove = JapaneseRomajiToHangul.transliterateLatinFragments("DEUS E AMOR")
        assertEquals(emptyList(), Regex("""[A-Za-z]+""").findAll(godIsLove).toList())
    }
}

package jp.co.crossmap.crawl.denomination

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.LocalizedName
import jp.co.crossmap.crawl.romajiToHangul

internal class PersonalNameTransliterator private constructor(
    private val surnames: Map<String, Reading>,
    private val givenNames: Map<String, Reading>,
) {
    private data class Reading(val kana: String, val romanized: String)
    private data class ParsedName(val surname: Reading?, val givenName: Reading?)

    fun localize(minister: ChurchMinister): ChurchMinister {
        val original = minister.name.trim()
        val parsed = parse(original)
        val surname = parsed?.surname
        val given = parsed?.givenName
        val latin = listOfNotNull(given?.romanized, surname?.romanized)
            .joinToString(" ") { it.toDisplayLatin() }
            .ifBlank { original }
        val korean = listOfNotNull(surname?.romanized, given?.romanized)
            .joinToString(" ") { romajiToHangul(it) ?: it }
            .ifBlank { original }
        val japaneseReading = listOfNotNull(surname?.kana, given?.kana).joinToString(" ")
        return minister.copy(
            localizedNames = buildList {
                add(LocalizedName("ja", original))
                if (japaneseReading.isNotBlank() && japaneseReading != original) add(LocalizedName("ja", japaneseReading))
                addAll(listOf(
                LocalizedName("en", latin),
                LocalizedName("ko", korean),
                LocalizedName("pt", latin),
                LocalizedName("id", latin),
                ))
            },
        )
    }

    private fun parse(value: String): ParsedName? {
        val components = value.replace('　', ' ').trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (components.size >= 2) {
            val surname = surnames[components.first()]
            val given = givenNames[components.drop(1).joinToString("")] ?: givenNames[components.last()]
            if (surname != null || given != null) return ParsedName(surname, given)
        }
        val compact = components.joinToString("")
        val surnameEntry = surnameKeys.firstOrNull { key -> compact.startsWith(key) && givenNames.containsKey(compact.removePrefix(key)) }
        if (surnameEntry != null) return ParsedName(surnames[surnameEntry], givenNames[compact.removePrefix(surnameEntry)])
        return surnames[compact]?.let { ParsedName(it, null) } ?: givenNames[compact]?.let { ParsedName(null, it) }
    }

    private val surnameKeys = surnames.keys.sortedByDescending(String::length)

    companion object {
        fun load(directory: Path): PersonalNameTransliterator {
            require(Files.isRegularFile(directory.resolve("README.md"))) { "Missing personal-name README: $directory" }
            val surnames = linkedMapOf<String, Reading>()
            Files.readAllLines(directory.resolve("last_name_org.csv")).forEach { line ->
                val columns = line.trimEnd('\r').split(',')
                if (columns.size >= 4) surnames.putIfAbsent(columns[0], Reading(columns[2], columns[3]))
            }
            val givenNames = linkedMapOf<String, Reading>()
            listOf(
                "first_name_man_opti.csv",
                "first_name_woman_opti.csv",
                "first_name_man_org.csv",
                "first_name_woman_org.csv",
            ).forEach { fileName ->
                Files.readAllLines(directory.resolve(fileName)).forEach { line ->
                    val columns = line.trimEnd('\r').split(',')
                    if (columns.size >= 3) {
                        val reading = Reading(columns[0], columns[1])
                        columns.drop(2).filter(String::isNotBlank).forEach { spelling -> givenNames.putIfAbsent(spelling, reading) }
                    }
                }
            }
            return PersonalNameTransliterator(surnames, givenNames)
        }
    }
}

private fun String.toDisplayLatin(): String = lowercase().replaceFirstChar { it.titlecase() }

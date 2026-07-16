package jp.co.crossmap

import java.text.Normalizer

/** Derives concise English address prefixes only for church-page slug collisions. */
internal object ChurchPageCollisionResolver {
    fun resolve(
        churches: List<ChurchRecord>,
        denominationEnglishNames: Map<String, String>,
        geonameEnglishLexicon: Map<String, String>,
        generator: StaticSiteGenerator = StaticSiteGenerator(),
    ): Map<String, String> {
        val bySlug = churches.groupBy { church ->
            generator.pageSlug(
                denominationEnglishNames[church.denominationId]
                    ?.takeUnless { church.denominationId.isIndependentDenomination() },
                church.englishName,
            )
        }
        val usedFinalSlugs = bySlug.filterValues { it.size == 1 }.keys.map(String::lowercase).toMutableSet()
        return buildMap {
            bySlug.values.filter { it.size > 1 }.forEach { collision ->
                collision.sortedBy(ChurchRecord::id).forEach { church ->
                    val prefix = locationCandidates(church.address, geonameEnglishLexicon).firstOrNull { candidate ->
                        val denomination = denominationEnglishNames[church.denominationId]
                            ?.takeUnless { church.denominationId.isIndependentDenomination() }
                        generator.pageSlug(denomination, "$candidate ${church.englishName}").lowercase() !in usedFinalSlugs
                    }
                    require(!prefix.isNullOrBlank()) {
                        "No unique English city/address prefix for ${church.id} (${church.name}) at ${church.address}"
                    }
                    val denomination = denominationEnglishNames[church.denominationId]
                        ?.takeUnless { church.denominationId.isIndependentDenomination() }
                    usedFinalSlugs += generator.pageSlug(denomination, "$prefix ${church.englishName}").lowercase()
                    put(church.id, prefix)
                }
            }
        }
    }

    private fun locationCandidates(address: String, lexicon: Map<String, String>): List<String> {
        val matches = mutableListOf<Pair<Int, String>>()
        for (start in address.indices) {
            for (length in 2..minOf(16, address.length - start)) {
                lexicon[address.substring(start, start + length)]?.trim()?.takeIf(String::isNotBlank)?.let {
                    matches += length to it
                }
            }
        }
        val englishParts = matches.sortedByDescending(Pair<Int, String>::first).map(Pair<Int, String>::second).distinct()
        if (englishParts.isEmpty()) return emptyList()
        val addressNumbers = Normalizer.normalize(address, Normalizer.Form.NFKC)
            .split(Regex("""\D+""")).filter(String::isNotBlank).takeLast(3).joinToString("-")
        return buildList {
            englishParts.forEach(::add)
            for (count in 2..minOf(4, englishParts.size)) add(englishParts.take(count).joinToString(" "))
            if (addressNumbers.isNotBlank()) {
                englishParts.take(4).forEach { add("$it $addressNumbers") }
                add("${englishParts.take(2).joinToString(" ")} $addressNumbers")
            }
        }.distinct()
    }

    private fun String?.isIndependentDenomination(): Boolean =
        isNullOrBlank() || this == "NOT_DETERMINED" || this == "INDEPENDENT_CHURCH"
}

package jp.co.crossmap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

data class ResolvedGeoQuery(
    val textQuery: String,
    val locations: List<ResolvedLocation>,
)

class GeoNameResolver(private val geonames: List<GeoName>) {
    private data class Candidate(val matchedText: String, val geoname: GeoName)

    fun resolve(query: String, radiusOverrideKm: Double? = null, language: String = "ja"): ResolvedGeoQuery {
        val normalized = normalizeQuery(query)
        logger.trace { "geoname-resolve: input=$query, normalized=$normalized, language=$language" }
        val candidates = geonames.flatMap { geoname ->
            val jaMatches = (listOf(geoname.name, administrativeAlias(geoname.name)) + geoname.aliases)
                .distinct()
                .filter { it.length >= MIN_GEONAME_MATCH_LENGTH && normalized.contains(normalizeQuery(it)) }
                .map { Candidate(normalizeQuery(it), geoname) }
            val translationMatches = if (language != "ja") {
                val translated = geoname.translations[language]
                if (translated != null && translated.length >= MIN_GEONAME_MATCH_LENGTH && normalized.contains(normalizeQuery(translated))) {
                    listOf(Candidate(normalizeQuery(translated), geoname))
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            jaMatches + translationMatches
        }.sortedByDescending { it.matchedText.length }
        logger.trace { "geoname-resolve: ${candidates.size} candidate(s) matched: ${candidates.joinToString { "'${it.matchedText}' -> ${it.geoname.name}(${it.geoname.type})" }}" }

        val acceptedTexts = mutableListOf<String>()
        candidates.forEach { candidate ->
            if (candidate.matchedText in acceptedTexts) return@forEach
            if (acceptedTexts.none { it.contains(candidate.matchedText) || candidate.matchedText.contains(it) }) {
                acceptedTexts += candidate.matchedText
            }
        }
        val accepted = candidates.filter { it.matchedText in acceptedTexts }

        val prefectures = accepted.filter { it.geoname.type == GeoNameType.PREFECTURE }
        val municipalityCandidates = accepted.filter { it.geoname.type != GeoNameType.PREFECTURE }
        val selectedMunicipalities = municipalityCandidates.filter { municipality ->
            prefectures.isEmpty() || prefectures.any { it.geoname.prefectureCode == municipality.geoname.prefectureCode }
        }
        val selected = if (selectedMunicipalities.isNotEmpty()) {
            selectedMunicipalities
        } else {
            prefectures
        }

        var remaining = normalized
        acceptedTexts.sortedByDescending { it.length }.forEach { remaining = remaining.replace(it, " ") }
        remaining = remaining.replace(Regex("\\s+"), " ").trim()

        val resolved = ResolvedGeoQuery(
            textQuery = remaining,
            locations = selected.map {
                ResolvedLocation(
                    matchedText = it.matchedText,
                    code = it.geoname.code,
                    name = it.geoname.name,
                    type = it.geoname.type,
                    center = it.geoname.center,
                    radiusKm = radiusOverrideKm ?: it.geoname.coveringRadiusKm,
                )
            }.distinctBy { it.code },
        )
        logger.trace { "geoname-resolve: textQuery='${resolved.textQuery}', locations=${resolved.locations.joinToString { "${it.name}(${it.type}, code=${it.code}, center=${it.center.latitude},${it.center.longitude}, r=${it.radiusKm}km)" }}" }
        return resolved
    }

    companion object {
        private const val MIN_GEONAME_MATCH_LENGTH = 2

        private fun administrativeAlias(name: String): String = name
            .removeSuffix("都")
            .removeSuffix("道")
            .removeSuffix("府")
            .removeSuffix("県")
            .removeSuffix("市")
            .removeSuffix("区")
            .removeSuffix("町")
            .removeSuffix("村")

        fun normalizeQuery(value: String): String = buildString(value.length) {
            value.trim().forEach { char ->
                append(
                    when (char.code) {
                        0x3000 -> ' '
                        in 0xFF01..0xFF5E -> (char.code - 0xFEE0).toChar()
                        else -> char.lowercaseChar()
                    }
                )
            }
        }.replace(Regex("\\s+"), " ")

        fun distanceKm(first: GeoPoint, second: GeoPoint): Double {
            val earthRadiusKm = 6371.0088
            val lat1 = first.latitude * kotlin.math.PI / 180.0
            val lat2 = second.latitude * kotlin.math.PI / 180.0
            val dLat = lat2 - lat1
            val dLon = (second.longitude - first.longitude) * kotlin.math.PI / 180.0
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

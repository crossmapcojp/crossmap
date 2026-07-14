package jp.co.crossmap

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ResolvedGeoQuery(
    val textQuery: String,
    val locations: List<ResolvedLocation>,
)

class GeoNameResolver(private val geonames: List<GeoName>) {
    private data class Candidate(val matchedText: String, val geoname: GeoName)

    fun resolve(query: String, radiusOverrideKm: Double? = null): ResolvedGeoQuery {
        val normalized = normalizeQuery(query)
        val candidates = geonames.flatMap { geoname ->
            (listOf(geoname.name) + geoname.aliases)
                .distinct()
                .filter { it.isNotBlank() && normalized.contains(normalizeQuery(it)) }
                .map { Candidate(normalizeQuery(it), geoname) }
        }.sortedByDescending { it.matchedText.length }

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

        return ResolvedGeoQuery(
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
    }

    companion object {
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

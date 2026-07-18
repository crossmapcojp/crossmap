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
    val candidates: List<ResolvedLocation> = emptyList(),
    val selectionReason: String = "none",
    val explicitAdministrativeName: Boolean = false,
)

fun detectIntendedGeonameFromUserLocation(candidates: List<GeoName>, userLocation: GeoPoint): GeoName? =
    candidates.distinctBy { it.code }.minWithOrNull(
        compareBy<GeoName> { GeoNameResolver.distanceKm(userLocation, it.center) }
            .thenBy { it.code },
    )

class GeoNameResolver(private val geonames: List<GeoName>) {
    private data class Candidate(
        val matchedText: String,
        val geoname: GeoName,
        val explicitPrefecture: Boolean = false,
        val explicitAdministrative: Boolean = false,
    )

    private val japaneseReadingsByCode: Map<String, List<String>> by lazy {
        geonames.associate { geoname ->
            geoname.code to (
                listOf(geoname.name, administrativeAlias(geoname.name)) + geoname.aliases
                ).asSequence()
                .filter { name -> name.any(::isJapaneseCharacter) }
                .map(JapaneseReadingNormalizer::compactReading)
                .filter { it.length >= MIN_GEONAME_MATCH_LENGTH }
                .distinct()
                .toList()
        }
    }

    fun resolve(
        query: String,
        radiusOverrideKm: Double? = null,
        language: String = "ja",
        userLocation: GeoPoint? = null,
    ): ResolvedGeoQuery {
        val normalized = normalizeQuery(query)
        logger.trace { "geoname-resolve: input=$query, normalized=$normalized, language=$language" }
        val candidates = geonames.asSequence()
            .filterNot(::isNationwideGeoname)
            .flatMap { geoname ->
            val canonicalName = normalizeQuery(geoname.name)
            val canonicalMatches = canonicalName.takeIf {
                it.length >= MIN_GEONAME_MATCH_LENGTH && normalized.contains(it)
            }?.let {
                listOf(
                    Candidate(
                        it,
                        geoname,
                        geoname.type == GeoNameType.PREFECTURE,
                        hasExplicitAdministrativeSuffix(normalized, it, language),
                    )
                )
            }.orEmpty()
            val jaMatches = (listOf(administrativeAlias(geoname.name)) + geoname.aliases)
                .distinct()
                .filter { it.length >= MIN_GEONAME_MATCH_LENGTH && normalized.contains(normalizeQuery(it)) }
                .map {
                    val matchedText = normalizeQuery(it)
                    Candidate(
                        matchedText,
                        geoname,
                        explicitAdministrative = hasExplicitAdministrativeSuffix(normalized, matchedText, language),
                    )
                }
            val translationMatches = if (language != "ja") {
                val translated = geoname.translations[language]
                if (translated != null && translated.length >= MIN_GEONAME_MATCH_LENGTH && normalized.contains(normalizeQuery(translated))) {
                    val translatedName = normalizeQuery(translated)
                    val matchedText = explicitAdministrativeMatch(normalized, translatedName, language) ?: translatedName
                    listOf(
                        Candidate(
                            matchedText,
                            geoname,
                            geoname.type == GeoNameType.PREFECTURE &&
                                hasExplicitPrefectureSuffix(normalized, translatedName, language),
                            matchedText != translatedName ||
                                hasExplicitAdministrativeSuffix(normalized, matchedText, language),
                        )
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val readingMatches = if (language == "ja") {
                japaneseReadingsByCode[geoname.code].orEmpty()
                    .filter(normalized::contains)
                    .map { matchedText -> Candidate(matchedText, geoname) }
            } else {
                emptyList()
            }
                (canonicalMatches + jaMatches + translationMatches + readingMatches).asSequence()
            }
            .distinctBy { it.matchedText to it.geoname.code }
            .sortedByDescending { it.matchedText.length }
            .toList()
        logger.trace { "geoname-resolve: ${candidates.size} candidate(s) matched: ${candidates.joinToString { "'${it.matchedText}' -> ${it.geoname.name}(${it.geoname.type})" }}" }

        val acceptedTexts = mutableListOf<String>()
        candidates.forEach { candidate ->
            if (candidate.matchedText in acceptedTexts) return@forEach
            if (acceptedTexts.none { it.contains(candidate.matchedText) || candidate.matchedText.contains(it) }) {
                acceptedTexts += candidate.matchedText
            }
        }
        val accepted = candidates.filter { it.matchedText in acceptedTexts }

        val explicitPrefectures = accepted.filter { it.explicitPrefecture }
        val municipalityCandidates = accepted.filter { it.geoname.type != GeoNameType.PREFECTURE }
        val intendedCandidates = when {
            explicitPrefectures.isNotEmpty() -> explicitPrefectures
            municipalityCandidates.isNotEmpty() -> municipalityCandidates
            else -> accepted.filter { it.geoname.type == GeoNameType.PREFECTURE }
        }.distinctBy { it.geoname.code }
        val selected = when {
            intendedCandidates.size == 1 -> intendedCandidates.single()
            intendedCandidates.size > 1 && userLocation != null -> {
                val nearest = detectIntendedGeonameFromUserLocation(
                    intendedCandidates.map { it.geoname },
                    userLocation,
                )
                intendedCandidates.firstOrNull { it.geoname.code == nearest?.code }
            }
            else -> null
        }
        val selectionReason = when {
            selected == null && intendedCandidates.isNotEmpty() -> "ambiguous-no-user-location"
            selected == null -> "none"
            explicitPrefectures.any { it.geoname.code == selected.geoname.code } -> "explicit-prefecture"
            intendedCandidates.size > 1 -> "nearest-user-location"
            selected.geoname.type == GeoNameType.PREFECTURE -> "unique-prefecture"
            else -> "unique-municipality"
        }

        var remaining = normalized
        acceptedTexts.sortedByDescending { it.length }.forEach { remaining = remaining.replace(it, " ") }
        remaining = remaining.replace(Regex("\\s+"), " ").trim()

        val resolved = ResolvedGeoQuery(
            textQuery = remaining,
            locations = listOfNotNull(selected?.toResolvedLocation(radiusOverrideKm)),
            candidates = intendedCandidates.map { it.toResolvedLocation(radiusOverrideKm) },
            selectionReason = selectionReason,
            explicitAdministrativeName = selected?.explicitAdministrative == true,
        )
        logger.trace { "geoname-resolve: textQuery='${resolved.textQuery}', locations=${resolved.locations.joinToString { "${it.name}(${it.type}, code=${it.code}, center=${it.center.latitude},${it.center.longitude}, r=${it.radiusKm}km)" }}" }
        return resolved
    }

    /** Returns the closest local-government area for a device-location result label. */
    fun nearestAdministrativeArea(point: GeoPoint): GeoName? = geonames.asSequence()
        .filter { it.type == GeoNameType.MUNICIPALITY || it.type == GeoNameType.WARD }
        .minWithOrNull(compareBy<GeoName> { distanceKm(point, it.center) }.thenBy { it.code })

    fun localizedName(geoname: GeoName, language: String): String =
        if (language == Language.JAPANESE.code) geoname.name else geoname.translations[language]
            ?: geoname.translations[Language.ENGLISH.code]
            ?: geoname.name

    private fun isNationwideGeoname(geoname: GeoName): Boolean =
        sequenceOf(geoname.name)
            .plus(geoname.aliases.asSequence())
            .plus(geoname.translations.values.asSequence())
            .map(::normalizeQuery)
            .any(NATIONWIDE_GEONAME_ALIASES::contains)

    private fun Candidate.toResolvedLocation(radiusOverrideKm: Double?): ResolvedLocation = ResolvedLocation(
        matchedText = matchedText,
        code = geoname.code,
        name = geoname.name,
        type = geoname.type,
        center = geoname.center,
        radiusKm = radiusOverrideKm ?: geoname.coveringRadiusKm,
    )

    companion object {
        private const val MIN_GEONAME_MATCH_LENGTH = 2

        // A nationwide filter cannot narrow a Japan-only catalog and causes denomination names such
        // as 日本基督教団 and 日本バプテスト連盟 to be misread as location queries.
        private val NATIONWIDE_GEONAME_ALIASES = setOf(
            "日本", "日本国", "japan", "일본", "japão", "japao", "jepang",
        )

        private val PREFECTURE_SUFFIXES = mapOf(
            "en" to listOf(" prefecture", "-prefecture", " ken", "-ken", " fu", "-fu", " to", "-to", " do", "-do"),
            "ko" to listOf("현", "도", "부"),
            "pt" to listOf(" prefeitura", " província", " provincia"),
            "id" to listOf(" prefektur", " provinsi"),
        )

        private fun hasExplicitAdministrativeSuffix(
            normalizedQuery: String,
            matchedText: String,
            language: String,
        ): Boolean = explicitAdministrativeMatch(normalizedQuery, matchedText, language) != null

        private fun explicitAdministrativeMatch(
            normalizedQuery: String,
            matchedText: String,
            language: String,
        ): String? {
            if (language == "ja" && Regex("[都道府県市区町村郡]$").containsMatchIn(matchedText)) return matchedText
            if (language == "ko" && Regex("[도현부시구정촌군]$").containsMatchIn(matchedText)) return matchedText

            val localizedSuffixes = when (language) {
                "en" -> listOf("prefecture", "city", "town", "village", "ward", "county")
                "pt" -> listOf("prefeitura", "província", "provincia", "cidade", "município", "municipio", "vila", "distrito")
                "id" -> listOf("prefektur", "provinsi", "kota", "kabupaten", "desa", "distrik")
                else -> emptyList()
            }
            val romanizedJapaneseSuffixes = listOf(
                "ken", "to", "dō", "do", "fu", "shi", "ku", "chō", "cho", "chou",
                "machi", "mura", "son", "gun",
            )
            val suffixPattern = (localizedSuffixes + romanizedJapaneseSuffixes)
                .distinct()
                .joinToString("|") { Regex.escape(it) }
            if (suffixPattern.isBlank()) return null
            return Regex(
                "${Regex.escape(matchedText)}(?:-|\\s)?(?:$suffixPattern)(?=\\s|$)",
                RegexOption.IGNORE_CASE,
            ).find(normalizedQuery)?.value
        }

        private fun hasExplicitPrefectureSuffix(query: String, matchedText: String, language: String): Boolean =
            PREFECTURE_SUFFIXES[language.substringBefore('-').lowercase()].orEmpty()
                .any { suffix -> query.contains(matchedText + suffix) }

        private fun administrativeAlias(name: String): String = name
            .removeSuffix("都")
            .removeSuffix("道")
            .removeSuffix("府")
            .removeSuffix("県")
            .removeSuffix("市")
            .removeSuffix("区")
            .removeSuffix("町")
            .removeSuffix("村")

        private fun isJapaneseCharacter(char: Char): Boolean =
            char in '\u3040'..'\u30ff' || char in '\u3400'..'\u9fff'

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

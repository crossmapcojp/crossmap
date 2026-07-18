package jp.co.crossmap

import kotlinx.serialization.Serializable

/** Best-effort structural decomposition of a Google Maps-style Japanese address. */
@Serializable
data class JapaneseAddress(
    val original: String,
    val normalized: String,
    val postalCode: String? = null,
    val prefecture: String? = null,
    val prefectureCode: String? = null,
    val county: String? = null,
    val municipality: String? = null,
    val municipalityCode: String? = null,
    val cityWard: String? = null,
    val cityWardCode: String? = null,
    val kyotoStreet: String? = null,
    val locality: String? = null,
    val addressNumber: String? = null,
    val building: String? = null,
)

object JapaneseAddressNormalizer {
    fun normalize(address: String, geonames: List<GeoName> = emptyList()): JapaneseAddress {
        val normalized = normalizeCharacters(address)
        val postalMatch = POSTAL_CODE.find(normalized)
        val postalCode = postalMatch?.groupValues?.get(1)
        var remaining = normalized.removePrefix(postalMatch?.value.orEmpty()).trim()

        val prefectureGeoName = longestPrefix(
            remaining,
            geonames.filter { it.type == GeoNameType.PREFECTURE },
        )
        val prefecture = prefectureGeoName?.name ?: PREFECTURE.find(remaining)?.value
        if (prefecture != null) remaining = remaining.removePrefix(prefecture).trimStart()
        val prefectureCode = prefectureGeoName?.code
        val scoped = geonames.filter { prefectureCode == null || it.prefectureCode == prefectureCode }

        val county = COUNTY.find(remaining)?.value
        if (county != null) remaining = remaining.removePrefix(county).trimStart()

        var municipalityGeoName = longestPrefix(
            remaining,
            scoped.filter { it.type == GeoNameType.MUNICIPALITY },
        )
        var wardGeoName = if (municipalityGeoName == null) {
            longestPrefix(remaining, scoped.filter { it.type == GeoNameType.WARD })
        } else {
            null
        }
        var municipality = municipalityGeoName?.name
        if (municipality == null && wardGeoName == null) {
            municipality = MUNICIPALITY.find(remaining)?.value
            municipalityGeoName = municipality?.let { name -> scoped.firstOrNull { it.name == name } }
        }
        if (municipality != null) remaining = remaining.removePrefix(municipality).trimStart()

        if (wardGeoName == null) {
            wardGeoName = longestPrefix(remaining, scoped.filter { it.type == GeoNameType.WARD })
        }
        var ward = wardGeoName?.name
        if (ward == null) {
            ward = WARD.find(remaining)?.value
            wardGeoName = ward?.let { name -> scoped.firstOrNull { it.name == name } }
        }
        if (ward != null) remaining = remaining.removePrefix(ward).trimStart()

        // Tokyo's special wards are municipality-level address entities; designated-city wards are children.
        val cityWard = ward.takeIf { municipality != null }
        val cityWardCode = wardGeoName?.code.takeIf { cityWard != null }
        if (municipality == null && ward != null) {
            municipality = ward
            municipalityGeoName = wardGeoName
        }

        val kyotoMatch = KYOTO_STREET.find(remaining)
        val kyotoStreet = kyotoMatch?.value
        if (kyotoStreet != null) remaining = remaining.removePrefix(kyotoStreet).trimStart()

        val firstDigit = remaining.indexOfFirst(Char::isDigit)
        val locality: String?
        val addressNumber: String?
        val building: String?
        if (firstDigit >= 0) {
            locality = remaining.substring(0, firstDigit).trim().ifBlank { null }
            val numberAndBuilding = remaining.substring(firstDigit).trim()
            val numberMatch = ADDRESS_NUMBER.find(numberAndBuilding)
            addressNumber = numberMatch?.value?.ifBlank { null }
            building = numberAndBuilding.removePrefix(numberMatch?.value.orEmpty()).trim().ifBlank { null }
        } else {
            val parts = remaining.split(Regex("""\s+"""), limit = 2)
            locality = parts.firstOrNull()?.ifBlank { null }
            addressNumber = null
            building = parts.getOrNull(1)?.ifBlank { null }
        }

        return JapaneseAddress(
            original = address,
            normalized = normalized,
            postalCode = postalCode,
            prefecture = prefecture,
            prefectureCode = prefectureCode,
            county = county,
            municipality = municipality,
            municipalityCode = municipalityGeoName?.code,
            cityWard = cityWard,
            cityWardCode = cityWardCode,
            kyotoStreet = kyotoStreet,
            locality = locality,
            addressNumber = addressNumber,
            building = building,
        )
    }

    private fun longestPrefix(value: String, geonames: List<GeoName>): GeoName? = geonames.asSequence()
        .filter { value.startsWith(it.name) }
        .maxByOrNull { it.name.length }

    private fun normalizeCharacters(value: String): String = buildString(value.length) {
        value.trim().forEach { character ->
            append(
                when {
                    character.code in 0xFF10..0xFF19 -> (character.code - 0xFEE0).toChar()
                    character in JAPANESE_HYPHENS -> '-'
                    character.code == 0x3000 -> ' '
                    else -> character
                }
            )
        }
    }.replace(Regex("""\s+"""), " ")

    private val POSTAL_CODE = Regex("""^〒?\s*(\d{3}-\d{4})\s*""")
    private val PREFECTURE = Regex("""^(?:東京都|北海道|大阪府|京都府|.{2,3}県)""")
    private val COUNTY = Regex("""^.+?郡""")
    private val MUNICIPALITY = Regex("""^.+?[市町村]""")
    private val WARD = Regex("""^.+?区""")
    private val KYOTO_STREET = Regex("""^.+?(?:上る|下る|上ル|下ル)(?:東入(?:る)?|西入(?:る)?)?""")
    private val ADDRESS_NUMBER = Regex("""^\d+(?:丁目)?(?:\d+)?(?:-\d+)*(?:番地?|番|号)?""")
    private val JAPANESE_HYPHENS = setOf('−', 'ー', '―', '‐', '‑', '–', '—', 'ｰ')
}

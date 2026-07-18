package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoName
import jp.co.crossmap.GeoNameResolver
import jp.co.crossmap.GeoNameType
import jp.co.crossmap.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeoCatalogBuilder(private val json: Json = Json { prettyPrint = true; encodeDefaults = true }) {
    @Serializable
    private data class LocalGovernmentOffice(
        val code: String,
        val name: String,
        val type: GeoNameType,
        val prefectureCode: String,
        val officeName: String? = null,
        val address: String? = null,
        val center: GeoPoint,
        val source: String = "UNKNOWN",
        val sourceDate: String? = null,
        val updatedAt: String? = null,
    )

    private data class MunicipalitySource(
        val code: String,
        val name: String,
        val addressMatch: String = name,
        val aliases: List<String> = emptyList(),
        val translations: Map<String, String> = emptyMap(),
    )

    fun build(
        churches: List<ChurchRecord>,
        japaneseCitiesSource: Path,
        output: Path,
        multilingualLexicon: Map<String, Map<String, String>> = emptyMap(),
        localGovernmentOffices: Path = output.resolveSibling("japanese-local-goverment-offices.json"),
    ): List<GeoName> {
        val officeByCode = if (Files.isRegularFile(localGovernmentOffices)) {
            json.decodeFromString<List<LocalGovernmentOffice>>(Files.readString(localGovernmentOffices)).associateBy { it.code }
        } else {
            emptyMap()
        }
        val municipalities = enrichDesignatedCityWards(
            parseMunicipalities(Files.readString(japaneseCitiesSource)),
            churches,
        )
        val aliasCounts = municipalities.groupingBy { stripSuffix(it.name) }.eachCount()
        val prefectureEntries = prefectures.mapIndexed { index, name ->
            val code = (index + 1).toString().padStart(2, '0')
            val matching = churches.filter {
                it.address.contains(name) && isIncludedInPrefectureSearch(code, it.address)
            }
            val translations = multilingualLexicon[name].orEmpty()
            val office = officeByCode[code]
            fromPoints(
                code, name, GeoNameType.PREFECTURE, code, emptyList(), matching.map { it.location }, japanCenter, translations,
                centerOverride = office?.center,
            )
        }
        val prefectureByCode = prefectureEntries.associateBy { it.code }
        val cityEntries = municipalities.map { municipality ->
            val code = municipality.code
            val name = municipality.name
            val prefectureCode = code.take(2)
            val prefectureName = prefectures.getOrNull(prefectureCode.toIntOrNull()?.minus(1) ?: -1).orEmpty()
            val matching = churches.filter { church ->
                church.address.contains(municipality.addressMatch) &&
                    (prefectureName.isBlank() || church.address.contains(prefectureName))
            }
            val shortAlias = stripSuffix(name).takeIf { it != name && aliasCounts[it] == 1 }
            val aliases = (municipality.aliases + listOfNotNull(shortAlias)).distinct().filter { it != name }
            val translations = municipality.translations + multilingualLexicon[name].orEmpty()
            val office = officeByCode[code]
            fromPoints(
                code,
                name,
                if (name.endsWith("区")) GeoNameType.WARD else GeoNameType.MUNICIPALITY,
                prefectureCode,
                aliases,
                matching.map { it.location },
                prefectureByCode[prefectureCode]?.center ?: japanCenter,
                translations,
                includeInPrefectureSearch(prefectureCode, name),
                office?.center,
            )
        }
        val result = (prefectureEntries + cityEntries).sortedBy { it.code }
        Files.createDirectories(output.parent)
        Files.writeString(output, json.encodeToString(result))
        return result
    }

    private fun parseMunicipalities(source: String): List<MunicipalitySource> =
        if (source.trimStart().startsWith("{")) parseJmaMunicipalities(source) else parseKotlinMunicipalities(source)

    private data class CityWardAddress(val prefectureCode: String, val city: String, val ward: String)

    private fun enrichDesignatedCityWards(
        municipalities: List<MunicipalitySource>,
        churches: List<ChurchRecord>,
    ): List<MunicipalitySource> {
        val addressWards = churches.mapNotNull { church ->
            val prefecture = prefectures.withIndex().firstOrNull { church.address.contains(it.value) } ?: return@mapNotNull null
            val withoutPrefecture = church.address.substringAfter(prefecture.value)
            val match = DESIGNATED_CITY_WARD.find(withoutPrefecture) ?: return@mapNotNull null
            CityWardAddress(
                prefectureCode = (prefecture.index + 1).toString().padStart(2, '0'),
                city = match.groupValues[1],
                ward = match.groupValues[2],
            )
        }.distinct()
        val enriched = municipalities.map { municipality ->
            if (!municipality.name.endsWith("区")) return@map municipality
            val match = addressWards.firstOrNull { addressWard ->
                municipality.code.startsWith(addressWard.prefectureCode) &&
                    municipality.name.startsWith(addressWard.city.removeSuffix("市")) &&
                    municipality.name.endsWith(addressWard.ward)
            } ?: return@map municipality
            municipality.copy(
                name = match.ward,
                addressMatch = match.city + match.ward,
                aliases = (municipality.aliases + listOf(municipality.name, match.city + match.ward)).distinct(),
            )
        }
        val existingNames = enriched.map(MunicipalitySource::name).toSet()
        val parentCities = addressWards.mapNotNull { addressWard ->
            if (addressWard.city in existingNames) return@mapNotNull null
            val child = enriched.firstOrNull {
                it.code.startsWith(addressWard.prefectureCode) && it.addressMatch == addressWard.city + addressWard.ward
            } ?: return@mapNotNull null
            MunicipalitySource(
                code = jisMunicipalityCode(child.code.take(4) + "0"),
                name = addressWard.city,
            )
        }
        return (enriched + parentCities).distinctBy { it.code }
    }

    private fun parseKotlinMunicipalities(source: String): List<MunicipalitySource> =
        Regex("""(\d+)\s+to\s+\"([^\"]+)\"""").findAll(source)
            .map { MunicipalitySource(it.groupValues[1].padStart(6, '0'), it.groupValues[2]) }
            .distinctBy { it.code }
            .toList()

    /**
     * JMA uses seven-digit area keys without the JIS check digit and prefixes designated-city wards
     * with their parent city (for example `4013300 -> 福岡中央区`). Crossmap restores the official
     * six-digit local-government code and indexes the ward as `中央区`, retaining parent-qualified
     * aliases such as `福岡市中央区` for longest-match resolution.
     */
    private fun parseJmaMunicipalities(source: String): List<MunicipalitySource> {
        data class JmaRow(
            val key: String,
            val japanese: String,
            val translations: Map<String, String>,
        )

        val rows = json.parseToJsonElement(source).jsonObject.mapNotNull { (key, value) ->
            val fields = value.jsonObject
            val japanese = fields["japanese"]?.jsonPrimitive?.content.orEmpty().trim()
            if (key.length < 5 || japanese.isBlank()) return@mapNotNull null
            JmaRow(
                key = key,
                japanese = japanese,
                translations = mapOf(
                    "en" to fields["english"]?.jsonPrimitive?.content.orEmpty(),
                    "ko" to fields["korean"]?.jsonPrimitive?.content.orEmpty(),
                    "pt" to fields["portuguese"]?.jsonPrimitive?.content.orEmpty(),
                    "id" to fields["indonesian"]?.jsonPrimitive?.content.orEmpty(),
                ).filterValues(String::isNotBlank),
            )
        }
        val cities = rows.filter { it.japanese.endsWith("市") }
        return rows.map { row ->
            val prefectureCode = row.key.take(2)
            val parentCity = cities.asSequence()
                .filter { it.key.take(2) == prefectureCode }
                .filter { row.japanese.startsWith(it.japanese.removeSuffix("市")) }
                .maxByOrNull { it.japanese.length }
                .takeIf { row.japanese.endsWith("区") }
            val tokyoWardPrefix = row.japanese.takeIf {
                prefectureCode == TOKYO_PREFECTURE_CODE && it.startsWith("東京") && it.endsWith("区")
            }?.let { "東京" }
            val canonicalName = when {
                parentCity != null -> row.japanese.removePrefix(parentCity.japanese.removeSuffix("市"))
                tokyoWardPrefix != null -> row.japanese.removePrefix(tokyoWardPrefix)
                else -> row.japanese
            }
            val addressMatch = parentCity?.let { it.japanese + canonicalName } ?: canonicalName
            MunicipalitySource(
                code = jisMunicipalityCode(row.key.take(5)),
                name = canonicalName,
                addressMatch = addressMatch,
                aliases = listOf(row.japanese, addressMatch).distinct().filter { it != canonicalName },
                translations = row.translations,
            )
        }.distinctBy { it.code }
    }

    private fun jisMunicipalityCode(baseCode: String): String {
        require(baseCode.length == 5 && baseCode.all(Char::isDigit)) { "Invalid JMA municipality key: $baseCode" }
        val weighted = baseCode.mapIndexed { index, character -> character.digitToInt() * (6 - index) }.sum()
        val candidate = 11 - weighted % 11
        val checkDigit = if (candidate >= 10) 0 else candidate
        return baseCode + checkDigit
    }

    private fun fromPoints(
        code: String,
        name: String,
        type: GeoNameType,
        prefectureCode: String,
        aliases: List<String>,
        points: List<GeoPoint>,
        fallback: GeoPoint,
        translations: Map<String, String> = emptyMap(),
        includeInPrefectureSearch: Boolean = true,
        centerOverride: GeoPoint? = null,
    ): GeoName {
        val center = centerOverride ?: if (points.isEmpty()) fallback else GeoPoint(
            points.map { it.latitude }.average(),
            points.map { it.longitude }.average(),
        )
        val radius = points.maxOfOrNull { GeoNameResolver.distanceKm(center, it) }
            ?.plus(10.0)?.coerceAtLeast(15.0)
            ?: 50.0
        return GeoName(
            code,
            name,
            aliases,
            type,
            prefectureCode,
            center,
            radius,
            translations,
            includeInPrefectureSearch,
        )
    }

    private fun isIncludedInPrefectureSearch(prefectureCode: String, address: String): Boolean =
        prefectureCode != TOKYO_PREFECTURE_CODE || tokyoRemoteIslandMunicipalities.none(address::contains)

    private fun includeInPrefectureSearch(prefectureCode: String, municipalityName: String): Boolean =
        prefectureCode != TOKYO_PREFECTURE_CODE || municipalityName !in tokyoRemoteIslandMunicipalities

    private fun stripSuffix(name: String): String = name.removeSuffix("市").removeSuffix("区")
        .removeSuffix("町").removeSuffix("村")

    companion object {
        private const val TOKYO_PREFECTURE_CODE = "13"
        private val japanCenter = GeoPoint(36.2048, 138.2529)
        private val DESIGNATED_CITY_WARD = Regex("""^([^都道府県市区町村郡\\s]+市)([^都道府県市区町村郡\\s]+区)""")
        private val tokyoRemoteIslandMunicipalities = setOf(
            "大島町",
            "利島村",
            "新島村",
            "神津島村",
            "三宅村",
            "御蔵島村",
            "八丈町",
            "青ヶ島村",
            "小笠原村",
        )
        private val prefectures = listOf(
            "北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県",
            "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県",
            "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県",
            "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県", "香川県", "愛媛県", "高知県", "福岡県",
            "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県",
        )
    }
}

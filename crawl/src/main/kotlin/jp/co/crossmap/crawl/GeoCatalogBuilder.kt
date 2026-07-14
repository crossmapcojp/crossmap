package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoName
import jp.co.crossmap.GeoNameResolver
import jp.co.crossmap.GeoNameType
import jp.co.crossmap.GeoPoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GeoCatalogBuilder(private val json: Json = Json { prettyPrint = true; encodeDefaults = true }) {
    fun build(churches: List<ChurchRecord>, japaneseCitiesSource: Path, output: Path): List<GeoName> {
        val municipalities = parseMunicipalities(Files.readString(japaneseCitiesSource))
        val aliasCounts = municipalities.groupingBy { stripSuffix(it.second) }.eachCount()
        val prefectureEntries = prefectures.mapIndexed { index, name ->
            val code = (index + 1).toString().padStart(2, '0')
            val matching = churches.filter { it.address.contains(name) }
            fromPoints(code, name, GeoNameType.PREFECTURE, code, emptyList(), matching.map { it.location }, japanCenter)
        }
        val prefectureByCode = prefectureEntries.associateBy { it.code }
        val cityEntries = municipalities.map { (code, name) ->
            val prefectureCode = code.take(2)
            val prefectureName = prefectures.getOrNull(prefectureCode.toIntOrNull()?.minus(1) ?: -1).orEmpty()
            val matching = churches.filter { church ->
                church.address.contains(name) && (prefectureName.isBlank() || church.address.contains(prefectureName))
            }
            val alias = stripSuffix(name).takeIf { it != name && aliasCounts[it] == 1 }?.let(::listOf).orEmpty()
            fromPoints(
                code,
                name,
                if (name.endsWith("区")) GeoNameType.WARD else GeoNameType.MUNICIPALITY,
                prefectureCode,
                alias,
                matching.map { it.location },
                prefectureByCode[prefectureCode]?.center ?: japanCenter,
            )
        }
        val result = (prefectureEntries + cityEntries).sortedBy { it.code }
        Files.createDirectories(output.parent)
        Files.writeString(output, json.encodeToString(result))
        return result
    }

    private fun parseMunicipalities(source: String): List<Pair<String, String>> =
        Regex("""(\d+)\s+to\s+\"([^\"]+)\"""").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinctBy { it.first }
            .toList()

    private fun fromPoints(
        code: String,
        name: String,
        type: GeoNameType,
        prefectureCode: String,
        aliases: List<String>,
        points: List<GeoPoint>,
        fallback: GeoPoint,
    ): GeoName {
        val center = if (points.isEmpty()) fallback else GeoPoint(
            points.map { it.latitude }.average(),
            points.map { it.longitude }.average(),
        )
        val radius = points.maxOfOrNull { GeoNameResolver.distanceKm(center, it) }
            ?.plus(10.0)?.coerceAtLeast(15.0)
            ?: 50.0
        return GeoName(code, name, aliases, type, prefectureCode, center, radius)
    }

    private fun stripSuffix(name: String): String = name.removeSuffix("市").removeSuffix("区")
        .removeSuffix("町").removeSuffix("村")

    companion object {
        private val japanCenter = GeoPoint(36.2048, 138.2529)
        private val prefectures = listOf(
            "北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県",
            "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県",
            "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県",
            "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県", "香川県", "愛媛県", "高知県", "福岡県",
            "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県",
        )
    }
}


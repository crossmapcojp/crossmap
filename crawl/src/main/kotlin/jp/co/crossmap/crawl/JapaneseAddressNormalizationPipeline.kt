package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import jp.co.crossmap.ChurchRecord
import jp.co.crossmap.GeoName
import jp.co.crossmap.JapaneseAddress
import jp.co.crossmap.JapaneseAddressNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AddressNormalizationRequest(
    val churchId: String,
    val address: String,
)

@Serializable
data class GeoloniaAddressResult(
    val churchId: String,
    val status: String,
    val pref: String? = null,
    val city: String? = null,
    val town: String? = null,
    val addr: String? = null,
    val other: String = "",
    val level: Int = 0,
    val error: String? = null,
)

@Serializable
data class NormalizedChurchAddress(
    val churchId: String,
    val churchName: String,
    val originalAddress: String,
    val status: String,
    val level: Int,
    val levelName: String,
    val geoloniaPrefecture: String? = null,
    val geoloniaCity: String? = null,
    val geoloniaTown: String? = null,
    val geoloniaAddressNumber: String? = null,
    val geoloniaRemainder: String = "",
    val normalizedAddress: JapaneseAddress,
    val error: String? = null,
)

@Serializable
data class JapaneseAddressNormalizationCache(
    val normalizer: String = "@geolonia/normalize-japanese-addresses",
    val geonameCatalogSha256: String = "",
    val entries: List<NormalizedChurchAddress>,
)

data class JapaneseAddressNormalizationReport(
    val entries: List<NormalizedChurchAddress>,
    val reused: Int,
    val reEnriched: Int,
) {
    val errors: List<NormalizedChurchAddress> get() = entries.filter { it.status != "success" }
    val levelCounts: Map<Int, Int> get() = entries.groupingBy { it.level }.eachCount().toSortedMap()
}

fun interface ChurchAddressBatchNormalizer {
    fun normalize(requests: List<AddressNormalizationRequest>): List<GeoloniaAddressResult>
}

class JapaneseAddressNormalizationPipeline(
    private val normalizer: ChurchAddressBatchNormalizer,
    private val batchSize: Int = 1_000,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    fun normalize(
        churches: List<ChurchRecord>,
        geonames: List<GeoName>,
        cacheFile: Path,
    ): JapaneseAddressNormalizationReport {
        val geonameCatalogSha256 = json.encodeToString(geonames.sortedBy(GeoName::code)).toByteArray().sha256()
        val cachedCache = if (Files.isRegularFile(cacheFile)) {
            json.decodeFromString<JapaneseAddressNormalizationCache>(Files.readString(cacheFile))
        } else {
            JapaneseAddressNormalizationCache(entries = emptyList())
        }
        val cached = cachedCache.entries.associateBy { it.churchId }
        val sameGeonameCatalog = cachedCache.geonameCatalogSha256 == geonameCatalogSha256
        val reusable = churches.mapNotNull { church ->
            cached[church.id]?.takeIf {
                sameGeonameCatalog && it.originalAddress == church.address && (it.status != "success" || it.level > 0)
            }
        }.associateBy { it.churchId }
        val reEnriched = if (sameGeonameCatalog) {
            emptyMap()
        } else {
            churches.mapNotNull { church ->
                cached[church.id]
                    ?.takeIf { it.originalAddress == church.address }
                    ?.let { compose(church, it.toGeoloniaResult(), geonames) }
            }.associateBy { it.churchId }
        }
        val pending = churches.filterNot { it.id in reusable || it.id in reEnriched }.filter { it.address.isNotBlank() }
        val churchesById = churches.associateBy { it.id }
        val generated = linkedMapOf<String, NormalizedChurchAddress>()
        pending.chunked(batchSize.coerceAtLeast(1)).forEach { batch ->
            normalizer.normalize(
                batch.map { AddressNormalizationRequest(it.id, prepareAddressForGeolonia(it.address)) },
            ).forEach { raw ->
                val church = churchesById[raw.churchId] ?: return@forEach
                generated[church.id] = compose(church, raw, geonames)
            }
            writeCache(cacheFile, reusable.values + reEnriched.values + generated.values, geonameCatalogSha256)
        }
        val entries = churches.map { church ->
            reusable[church.id] ?: reEnriched[church.id] ?: generated[church.id] ?: compose(
                church,
                GeoloniaAddressResult(
                    churchId = church.id,
                    status = "error",
                    error = if (church.address.isBlank()) "Address is blank" else "Normalizer returned no result",
                ),
                geonames,
            )
        }
        writeCache(cacheFile, entries, geonameCatalogSha256)
        return JapaneseAddressNormalizationReport(entries, reusable.size, reEnriched.size)
    }

    private fun writeCache(
        cacheFile: Path,
        entries: Collection<NormalizedChurchAddress>,
        geonameCatalogSha256: String,
    ) {
        Files.createDirectories(cacheFile.parent)
        val temporary = Files.createTempFile(cacheFile.parent, ".normalized-addresses-", ".json")
        Files.writeString(
            temporary,
            json.encodeToString(
                JapaneseAddressNormalizationCache(
                    geonameCatalogSha256 = geonameCatalogSha256,
                    entries = entries.sortedBy { it.churchId },
                ),
            ),
        )
        runCatching {
            Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun NormalizedChurchAddress.toGeoloniaResult(): GeoloniaAddressResult = GeoloniaAddressResult(
        churchId = churchId,
        status = if (status == "success") "success" else "error",
        pref = geoloniaPrefecture,
        city = geoloniaCity,
        town = geoloniaTown,
        addr = geoloniaAddressNumber,
        other = geoloniaRemainder,
        level = level,
        error = error,
    )

    private fun compose(
        church: ChurchRecord,
        raw: GeoloniaAddressResult,
        geonames: List<GeoName>,
    ): NormalizedChurchAddress {
        val reconstructed = listOfNotNull(raw.pref, raw.city, raw.town, raw.addr)
            .joinToString("") + raw.other
        val source = reconstructed.takeIf(String::isNotBlank) ?: church.address
        val normalized = JapaneseAddressNormalizer.normalize(source, geonames).copy(
            original = church.address,
            postalCode = JapaneseAddressNormalizer.normalize(church.address, geonames).postalCode,
        )
        val status = if (raw.status == "success" && raw.level > 0) "success" else "failed"
        return NormalizedChurchAddress(
            churchId = church.id,
            churchName = church.name,
            originalAddress = church.address,
            status = status,
            level = raw.level,
            levelName = addressNormalizationLevelName(raw.level),
            geoloniaPrefecture = raw.pref,
            geoloniaCity = raw.city,
            geoloniaTown = raw.town,
            geoloniaAddressNumber = raw.addr,
            geoloniaRemainder = raw.other,
            normalizedAddress = normalized,
            error = raw.error ?: if (raw.level == 0) "Geolonia could not identify the prefecture" else null,
        )
    }
}

class LocalGeoloniaAddressNormalizer(
    private val normalizerDirectory: Path,
    private val runner: Path,
    private val concurrency: Int = 4,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : ChurchAddressBatchNormalizer {
    override fun normalize(requests: List<AddressNormalizationRequest>): List<GeoloniaAddressResult> {
        if (requests.isEmpty()) return emptyList()
        val module = normalizerDirectory.resolve("dist/main-node-esm.mjs")
        if (!Files.isRegularFile(module)) buildLocalModule()
        require(Files.isRegularFile(module)) { "Geolonia build did not produce $module" }
        require(Files.isRegularFile(runner)) { "Crossmap Geolonia runner is missing: $runner" }
        val input = Files.createTempFile("crossmap-addresses-", ".json")
        return try {
            Files.writeString(input, json.encodeToString(requests))
            val process = ProcessBuilder(
                "node",
                runner.toAbsolutePath().normalize().toString(),
                module.toAbsolutePath().normalize().toString(),
                input.toString(),
                concurrency.coerceAtLeast(1).toString(),
            ).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "Local Geolonia normalizer failed: ${stderr.takeLast(4_000)}" }
            json.decodeFromString(stdout)
        } finally {
            Files.deleteIfExists(input)
        }
    }

    private fun buildLocalModule() {
        require(Files.isRegularFile(normalizerDirectory.resolve("package.json"))) {
            "Local Geolonia checkout was not found at $normalizerDirectory"
        }
        runBuildCommand("npm", "install")
        runBuildCommand("npm", "run", "build")
    }

    private fun runBuildCommand(vararg command: String) {
        val exit = ProcessBuilder(*command)
            .directory(normalizerDirectory.toFile())
            .inheritIO()
            .start()
            .waitFor()
        check(exit == 0) { "Command failed (${command.joinToString(" ")}): exit=$exit" }
    }
}

fun addressNormalizationLevelName(level: Int): String = when (level) {
    8 -> "address-number"
    3 -> "town"
    2 -> "city"
    1 -> "prefecture"
    else -> "failed"
}

internal fun prepareAddressForGeolonia(address: String): String = java.text.Normalizer
    .normalize(address, java.text.Normalizer.Form.NFKC)
    .trim()
    .replace(Regex("""[−ー―‐‑–—ｰ]"""), "-")
    .replace(Regex("""^〒?\s*\d{3}-\d{4}\s*"""), "")
    .replace(Regex("""\s+"""), " ")

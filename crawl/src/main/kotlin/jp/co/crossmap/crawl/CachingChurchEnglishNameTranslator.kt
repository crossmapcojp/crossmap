package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class CachedChurchEnglishName(
    val churchName: String,
    val denominationId: String? = null,
    val address: String = "",
    val websiteUrl: String,
    val guess: ChurchEnglishNameGuess,
)

@Serializable
private data class ChurchEnglishNameCache(
    val model: String,
    val entries: Map<String, CachedChurchEnglishName> = emptyMap(),
)

data class ChurchEnglishNameCacheStats(
    var hits: Int = 0,
    var translated: Int = 0,
    var batches: Int = 0,
    var errors: Int = 0,
    var timeouts: Int = 0,
)

/** Persists every completed LLM batch so long catalog cleanup is safely resumable. */
class CachingChurchEnglishNameTranslator(
    private val delegate: ChurchEnglishNameTranslator,
    private val model: String,
    private val cacheFile: Path,
    private val batchSize: Int = 64,
    private val onBatchCompleted: (ChurchEnglishNameCacheStats) -> Unit = {},
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) : ChurchEnglishNameTranslator {
    val stats = ChurchEnglishNameCacheStats()

    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    override suspend fun translate(church: ChurchEnglishNameInput): ChurchEnglishNameGuess {
        val entries = readEntries().toMutableMap()
        entries[church.id]?.takeIf { it.matches(church) }?.let {
            stats.hits++
            return it.guess
        }
        return runCatching { delegate.translate(church) }
            .onFailure(::recordFailure)
            .getOrThrow()
            .also { guess ->
                entries[church.id] = church.cached(guess)
                stats.translated++
                stats.batches++
                write(entries)
                onBatchCompleted(stats.copy())
            }
    }

    override suspend fun translateAll(
        churches: List<ChurchEnglishNameInput>,
    ): Map<String, ChurchEnglishNameGuess> {
        val entries = readEntries().toMutableMap()
        val result = linkedMapOf<String, ChurchEnglishNameGuess>()
        val pending = mutableListOf<ChurchEnglishNameInput>()
        churches.forEach { church ->
            entries[church.id]?.takeIf { it.matches(church) }?.let { cached ->
                stats.hits++
                result[church.id] = cached.guess
            } ?: pending.add(church)
        }
        pending.chunked(batchSize).forEach { batch ->
            val translated = runCatching { delegate.translateAll(batch) }
                .onFailure(::recordFailure)
                .getOrThrow()
            batch.forEach { church ->
                val guess = requireNotNull(translated[church.id]) {
                    "English-name translator returned no result for ${church.id} (${church.name})"
                }
                entries[church.id] = church.cached(guess)
                result[church.id] = guess
            }
            stats.translated += batch.size
            stats.batches++
            write(entries)
            onBatchCompleted(stats.copy())
        }
        return result
    }

    private fun readEntries(): Map<String, CachedChurchEnglishName> {
        if (!Files.isRegularFile(cacheFile)) return emptyMap()
        val cache = json.decodeFromString<ChurchEnglishNameCache>(Files.readString(cacheFile))
        return if (cache.model == model) cache.entries else emptyMap()
    }

    private fun CachedChurchEnglishName.matches(church: ChurchEnglishNameInput): Boolean =
        churchName == church.name && (denominationId == null || denominationId == church.denominationId) &&
            (address.isBlank() || address == church.address) && websiteUrl == church.websiteUrl

    private fun ChurchEnglishNameInput.cached(guess: ChurchEnglishNameGuess) =
        CachedChurchEnglishName(name, denominationId, address, websiteUrl, guess)

    private fun recordFailure(error: Throwable) {
        stats.errors++
        if (error.message.orEmpty().contains("timeout", ignoreCase = true) ||
            error::class.simpleName.orEmpty().contains("timeout", ignoreCase = true)
        ) {
            stats.timeouts++
        }
    }

    private fun write(entries: Map<String, CachedChurchEnglishName>) {
        Files.createDirectories(cacheFile.parent)
        val temporary = Files.createTempFile(cacheFile.parent, ".english-name-cache-", ".json")
        Files.writeString(temporary, json.encodeToString(ChurchEnglishNameCache(model, entries.toSortedMap())))
        runCatching {
            Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

package jp.co.crossmap.catalog.canonical

import java.security.MessageDigest
import jp.co.crossmap.ChurchRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CatalogRevisionToken(val revisionId: String, val revisionSequence: Long)

data class CatalogRevision(
    val revisionId: String,
    val revisionSequence: Long,
    val contentHash: String,
)

data class CatalogOperationMetadata(
    val operation: String,
    val actor: String,
    val source: String? = null,
)

data class CanonicalChurchCatalogSnapshot(
    val churches: List<ChurchRecord>,
    val revisionId: String,
    val revisionSequence: Long,
    val contentHash: String,
) {
    val revisionToken: CatalogRevisionToken get() = CatalogRevisionToken(revisionId, revisionSequence)
}

data class CatalogCommitResult(
    val revision: CatalogRevision,
    val changed: Boolean,
)

interface CanonicalChurchCatalogReader {
    suspend fun readCommittedSnapshot(): CanonicalChurchCatalogSnapshot
}

interface CanonicalChurchCatalogWriter {
    suspend fun replaceChurchCatalog(
        expectedRevision: CatalogRevisionToken?,
        churches: List<ChurchRecord>,
        operation: CatalogOperationMetadata,
    ): CatalogCommitResult
}

interface CatalogRevisionReader {
    suspend fun currentCommittedRevision(): CatalogRevision
}

class CatalogRevisionMismatchException(expected: CatalogRevisionToken, actual: CatalogRevisionToken?) :
    IllegalStateException("Catalog revision mismatch: expected=$expected actual=${actual ?: "none"}")

object CanonicalChurchCatalogHasher {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun normalized(churches: List<ChurchRecord>): List<ChurchRecord> = churches
        .map { church ->
            church.copy(
                localizedNames = church.localizedNames.sortedWith(compareBy({ it.languageCode }, { it.name })),
                localizedDenominationNames = church.localizedDenominationNames.sortedWith(compareBy({ it.languageCode }, { it.name })),
                titleLanguages = church.titleLanguages.distinct().sorted(),
                pages = church.pages.sortedBy { json.encodeToString(it) },
                socialProfiles = church.socialProfiles.sortedBy { json.encodeToString(it) },
                ministers = church.ministers.sortedBy { json.encodeToString(it) },
                determinations = church.determinations.sortedBy { json.encodeToString(it) },
            )
        }
        .sortedBy(ChurchRecord::id)

    fun logicalJson(churches: List<ChurchRecord>): String = json.encodeToString(normalized(churches))

    fun contentHash(churches: List<ChurchRecord>): String = MessageDigest.getInstance("SHA-256")
        .digest(logicalJson(churches).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

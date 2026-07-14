package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class EvidenceKind {
    GOOGLE_PLACE,
    CHURCH_WEBSITE,
    DENOMINATION_DIRECTORY,
    FACEBOOK,
    X,
    INSTAGRAM,
    YOUTUBE,
    SERMON_PAGE,
}

@Serializable
enum class EvidenceEntityType { CHURCH, DENOMINATION, SOCIAL_PROFILE, SERMON }

@Serializable
data class EvidenceRecord(
    val id: String,
    val kind: EvidenceKind,
    val entityType: EvidenceEntityType,
    val sourceId: String,
    val sourceUrl: String = "",
    val externalId: String? = null,
    val name: String = "",
    val address: String = "",
    val text: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val fetchedAt: String = "",
    val contentHash: String = "",
)

@Serializable
enum class CandidateRelationship { SAME_ENTITY, HAS_DENOMINATION, HAS_SOCIAL_PROFILE, HAS_SERMON }

@Serializable
data class EntityCandidateLink(
    val id: String,
    val leftEntityId: String,
    val rightEvidenceId: String,
    val relationship: CandidateRelationship,
    val programmaticScore: Double,
    val evidenceIds: List<String>,
)

@Serializable
enum class ResolutionStatus { ACCEPTED, REJECTED, NEEDS_REVIEW }

@Serializable
data class EntityResolution(
    val candidateId: String,
    val status: ResolutionStatus,
    val confidence: Double,
    val source: String,
    val reasoning: String = "",
    val decidedAt: String,
)

@Serializable
data class PipelineState(
    val schemaVersion: Int = 1,
    val completedStages: List<String> = emptyList(),
    val updatedAt: String = "",
)

data class PipelineContext(val resourcesRoot: Path, val store: EvidenceStore)

fun interface PipelineStage {
    suspend fun execute(context: PipelineContext)
}

data class NamedPipelineStage(val id: String, val stage: PipelineStage)

class EvidencePipelineRunner(
    private val stages: List<NamedPipelineStage>,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    suspend fun run(resourcesRoot: Path, resume: Boolean = true): PipelineState {
        val store = EvidenceStore(resourcesRoot, json)
        val stateFile = resourcesRoot.resolve("pipeline/state.json")
        var state = if (resume && Files.isRegularFile(stateFile)) {
            json.decodeFromString<PipelineState>(Files.readString(stateFile))
        } else PipelineState()
        stages.forEach { named ->
            if (resume && named.id in state.completedStages) return@forEach
            named.stage.execute(PipelineContext(resourcesRoot, store))
            state = state.copy(completedStages = state.completedStages + named.id, updatedAt = Instant.now().toString())
            store.write("pipeline/state.json", state)
        }
        return state
    }
}

class EvidenceStore(
    private val root: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true },
) {
    fun write(relative: String, value: PipelineState) = writeText(relative, json.encodeToString(value))
    fun writeEvidence(relative: String, value: List<EvidenceRecord>) = writeText(relative, json.encodeToString(value))
    fun writeCandidates(relative: String, value: List<EntityCandidateLink>) = writeText(relative, json.encodeToString(value))
    fun writeResolutions(relative: String, value: List<EntityResolution>) = writeText(relative, json.encodeToString(value))

    private fun writeText(relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        val part = path.resolveSibling("${path.fileName}.part")
        Files.writeString(part, content)
        runCatching { Files.move(part, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(part, path, StandardCopyOption.REPLACE_EXISTING) }
    }
}

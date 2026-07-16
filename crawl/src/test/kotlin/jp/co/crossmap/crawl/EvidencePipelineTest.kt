package jp.co.crossmap.crawl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class EvidencePipelineTest {
    @Test
    fun checkpointsCompletedStagesAndResumesWithoutRepeatingWork() = runBlocking {
        val root = Files.createTempDirectory("crossmap-pipeline")
        try {
            val calls = mutableListOf<String>()
            val runner = EvidencePipelineRunner(
                listOf(
                    NamedPipelineStage("discover") { calls += "discover" },
                    NamedPipelineStage("normalize") { calls += "normalize" },
                    NamedPipelineStage("resolve") { calls += "resolve" },
                )
            )

            assertEquals(
                listOf("discover", "normalize", "resolve"),
                runner.run(root, cacheRoot = root.resolve("cache")).completedStages,
            )
            runner.run(root, cacheRoot = root.resolve("cache"))
            assertEquals(listOf("discover", "normalize", "resolve"), calls)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

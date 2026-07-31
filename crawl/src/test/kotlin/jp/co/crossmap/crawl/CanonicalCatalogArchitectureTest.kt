package jp.co.crossmap.crawl

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertTrue

class CanonicalCatalogArchitectureTest {
    @Test
    fun productionCodeCannotUseLegacyChurchesJsonOutsideExplicitBoundaryCommands() {
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("crawl/src/main/kotlin")) }
        val productionRoots = listOf("crawl", "catalog", "server").map {
            repositoryRoot.resolve("$it/src/main/kotlin")
        }
        val violations = productionRoots.flatMap { root ->
            if (!Files.isDirectory(root)) return@flatMap emptyList()
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
                    .filter { it.name != "CatalogNeo4jCommands.kt" }
                    .filter { path ->
                        val source = Files.readString(path)
                        "catalog/churches.json" in source || "churchCatalog" in source
                    }
                    .map(repositoryRoot::relativize)
                    .toList()
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Legacy church JSON access is restricted to explicit bootstrap/export commands: $violations",
        )
    }
}

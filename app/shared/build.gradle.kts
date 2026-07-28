import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val prepareComposeI18n by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("resources/i18n")) {
        exclude("README.md")
        eachFile {
            path = path
                .replace("values-zh-Hans/", "values-zh-rCN/")
                .replace("values-zh-Hant/", "values-zh-rTW/")
        }
    }
    into(layout.buildDirectory.dir("generated/compose-i18n"))
}

val generatedUiCatalogDirectory = layout.buildDirectory.dir("generated/i18n-kotlin/commonMain")
val generateAppUiCatalog by tasks.registering {
    val sourceDirectory = rootProject.layout.projectDirectory.dir("resources/i18n")
    val outputFile = generatedUiCatalogDirectory.map { it.file("jp/co/crossmap/GeneratedAppUiMessages.kt") }
    inputs.dir(sourceDirectory)
    outputs.file(outputFile)
    doLast {
        val directories: List<java.nio.file.Path> = Files.list(sourceDirectory.asFile.toPath()).use { paths ->
            paths.filter(Files::isDirectory).sorted().toList()
        }
        val catalogs: Map<String, Map<String, String>> = directories.associate { directory ->
                val qualifier = directory.fileName.toString()
                val languageCode = if (qualifier == "values") "en" else qualifier.removePrefix("values-")
                val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(directory.resolve("strings.xml").toFile())
                val strings = linkedMapOf<String, String>()
                val nodes = document.documentElement.childNodes
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index)
                    if (node.nodeName == "string") {
                        strings[node.attributes.getNamedItem("name").nodeValue] = node.textContent
                    }
                }
                languageCode to strings
        }
        fun literal(value: String): String = "\"" + value
            .replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("$", "\\$") + "\""
        val source = buildString {
            appendLine("package jp.co.crossmap")
            appendLine()
            appendLine("internal object GeneratedAppUiMessages {")
            appendLine("    private val values: Map<String, Map<String, String>> = mapOf(")
            catalogs.forEach { (language, strings) ->
                appendLine("        ${literal(language)} to mapOf(")
                strings.forEach { (key, value) -> appendLine("            ${literal(key)} to ${literal(value)},") }
                appendLine("        ),")
            }
            appendLine("    )")
            appendLine("    fun text(language: Language, key: String, vararg arguments: Any): String {")
            appendLine("        var result = values.getValue(language.code).getValue(key)")
            appendLine("        arguments.forEachIndexed { index, value -> result = result.replace(\"%${'$'}{index + 1}\\${'$'}s\", value.toString()) }")
            appendLine("        return result")
            appendLine("    }")
            appendLine("}")
        }
        val target = outputFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.writeString(target, source)
    }
}

compose.resources {
    customDirectory("commonMain", layout.dir(prepareComposeI18n.map { it.destinationDir }))
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "jp.co.crossmap.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.get().kotlin.srcDir(generateAppUiCatalog.map { generatedUiCatalogDirectory.get().asFile })
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.ktor.clientCio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientDarwin)
        }
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationJson)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

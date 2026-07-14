plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "jp.co"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(projects.core)
    implementation(libs.clikt)
    implementation(libs.jsoup)
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
    implementation(libs.lucene.kmp.core)
    implementation(libs.lucene.kmp.analysis.kuromoji)
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "jp.co.crossmap.crawl.MainKt"
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("readGoogleSavedPlaces") {
    group = "crossmap"
    description = "Read Google Takeout Saved Places CSV files into standalone raw Crossmap seeds"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "read-google-saved-places",
        "--input",
        providers.gradleProperty("googleSavedPlaces")
            .orElse("resources/raw/google-saved-places/input")
            .get(),
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
}

val resolveGoogleSavedPlaces by tasks.registering(JavaExec::class) {
    group = "crossmap"
    description = "Resolve Google Saved Places seeds through the CID HTML cache and parse raw church candidates"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "resolve-google-saved-places",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--concurrency",
        providers.gradleProperty("googleMapsConcurrency").orElse("6").get(),
    )
    if (!providers.gradleProperty("googleMapsNetwork").orElse("false").get().toBoolean()) args("--offline")
}

tasks.register("googleSavedPlacesSource") {
    group = "crossmap"
    description = "Run the standalone Google Saved Places seed and Google Maps resolution stages"
    dependsOn("readGoogleSavedPlaces", resolveGoogleSavedPlaces)
    resolveGoogleSavedPlaces.configure { mustRunAfter("readGoogleSavedPlaces") }
}

tasks.register<JavaExec>("promoteGoogleSavedPlaces") {
    group = "crossmap"
    description = "Run existing Crossmap cleanup on resolved Google candidates and atomically promote a complete catalog"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "promote-google-saved-places",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--english-model",
        providers.gradleProperty("ollamaModel").orElse("cat-translate:7b-q4_k_m").get(),
        "--denomination-model",
        providers.gradleProperty("denominationModel").orElse("qwen3:1.7b").get(),
        "--limit",
        providers.gradleProperty("denominationLlmLimit").orElse("100").get(),
    )
}

tasks.register("googleSavedPlacesDataCleanup") {
    group = "crossmap"
    description = "Run Saved Places source resolution and the integrated Crossmap cleanup/promotion workflow"
    dependsOn("googleSavedPlacesSource", "promoteGoogleSavedPlaces")
    tasks.named("promoteGoogleSavedPlaces").configure { mustRunAfter("googleSavedPlacesSource") }
}

val dataCleanup by tasks.registering(JavaExec::class) {
    group = "crossmap"
    description = "Populate and validate every church English name with deterministic rules and CAT via Ollama"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "english-names",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--model",
        providers.gradleProperty("ollamaModel").orElse("cat-translate:7b-q4_k_m").get(),
    )
    doFirst {
        val exit = ProcessBuilder("df", "-h", "/media/joel/llms").inheritIO().start().waitFor()
        check(exit == 0) { "df failed; refusing to invoke Ollama" }
    }
}

tasks.register("populateChurchEnglishNames") {
    group = "crossmap"
    description = "Compatibility alias for dataCleanup"
    dependsOn(dataCleanup)
}

tasks.register<JavaExec>("populateDenominationEnglishNames") {
    group = "crossmap"
    description = "Generate the complete denomination ID to English name map with CAT via Ollama"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "denomination-english-names",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--model",
        providers.gradleProperty("ollamaModel").orElse("cat-translate:7b-q4_k_m").get(),
    )
    mustRunAfter(dataCleanup)
    doFirst {
        val exit = ProcessBuilder("df", "-h", "/media/joel/llms").inheritIO().start().waitFor()
        check(exit == 0) { "df failed; refusing to invoke Ollama" }
    }
}

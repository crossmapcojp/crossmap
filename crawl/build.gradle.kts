import java.util.Properties

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
    implementation(projects.catalog)
    implementation(libs.clikt)
    implementation(libs.jsoup)
    implementation(libs.playwright)
    implementation(libs.pdfbox)
    implementation(libs.koog.agents)
    implementation(libs.logback)
    implementation(libs.jsonic)
    implementation(libs.icu4j)
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
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

val googleSavedPlacesInput = providers.gradleProperty("crossmap.googleSavedPlaces")
    .orElse(providers.gradleProperty("googleSavedPlaces"))
    .orElse(
        providers.provider {
            localProperties.getProperty("crossmap.googleSavedPlaces")
                ?: "resources/raw/google-saved-places/input"
        },
    )

val geoloniaNormalizerDirectory = providers.gradleProperty("crossmap.geoloniaNormalizerDir")
    .orElse(
        providers.provider {
            localProperties.getProperty("crossmap.geoloniaNormalizerDir")
                ?: "/home/joel/code/normalize-japanese-addresses"
        },
    )

tasks.register<JavaExec>("readGoogleSavedPlaces") {
    group = "crossmap"
    description = "Read Google Takeout Saved Places CSV files into standalone raw Crossmap seeds"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "read-google-saved-places",
        "--input",
        googleSavedPlacesInput.get(),
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

resolveGoogleSavedPlaces.configure { mustRunAfter("readGoogleSavedPlaces") }

tasks.register("googleSavedPlacesSource") {
    group = "crossmap"
    description = "Run the standalone Google Saved Places seed and Google Maps resolution stages"
    dependsOn("readGoogleSavedPlaces", resolveGoogleSavedPlaces)
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
        "--website-cache-hours",
        providers.gradleProperty("websiteCacheHours").orElse((30 * 24).toString()).get(),
    )
    if (providers.gradleProperty("programmaticOnly").orElse("false").get().toBoolean()) args("--programmatic-only")
    if (providers.gradleProperty("skipDirectoryCrawl").orElse("false").get().toBoolean()) args("--skip-directory-crawl")
}

tasks.named("promoteGoogleSavedPlaces").configure { mustRunAfter("googleSavedPlacesSource") }

tasks.register("googleSavedPlacesDataCleanup") {
    group = "crossmap"
    description = "Run Saved Places source resolution and the integrated Crossmap cleanup/promotion workflow"
    dependsOn("googleSavedPlacesSource", "promoteGoogleSavedPlaces")
}

tasks.register("googleSavedPlaces") {
    group = "crossmap"
    description = "Run the complete Google Saved Places workflow (reads Takeout CSV files, resolves CIDs, and promotes clean catalog)"
    dependsOn("googleSavedPlacesDataCleanup")
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
    // following line does not work in CI it is speciic to joel's dev box so commenting out. codex, if you read this comment and you think it is ok, delete the commented following commented out code:
    /*doFirst {
        val exit = ProcessBuilder("df", "-h", "/media/joel/llms").inheritIO().start().waitFor()
        check(exit == 0) { "df failed; refusing to invoke Ollama" }
    }*/
}

tasks.register("populateChurchEnglishNames") {
    group = "crossmap"
    description = "Compatibility alias for dataCleanup"
    dependsOn(dataCleanup)
}

tasks.register<JavaExec>("buildSearchSnapshot") {
    group = "crossmap"
    description = "Rebuild the development church-search snapshot from the current canonical catalog"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "build-snapshot",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--version",
        providers.gradleProperty("crossmapIndexVersion").orElse("development").get(),
    )
    mustRunAfter(dataCleanup)
}

tasks.register<JavaExec>("prepareGeoNameCache") {
    group = "crossmap"
    description = "Download official JP.zip when needed and build the local Japanese GeoNames lexicon"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "prepare-geoname-cache",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
}

val buildGeoCatalog by tasks.registering(JavaExec::class) {
    group = "crossmap"
    description = "Build the runtime prefecture, municipality, and designated-city ward resolver catalog from JMA data"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    dependsOn("prepareGeoNameCache")
    args(
        "build-geonames",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--cities-source",
        providers.gradleProperty("jmaCitySource").orElse("resources/geonames/jma-city.json").get(),
    )
}

val prepareChurchGeoNames by tasks.registering(JavaExec::class) {
    group = "crossmap"
    description = "Collect title/address geonames and merge official/reviewed JA-EN-KO-PT-ID-VI translations"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    dependsOn(buildGeoCatalog)
    args(
        "church-geonames",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
}

val normalizeChurchAddresses by tasks.registering(JavaExec::class) {
    group = "crossmap"
    description = "Normalize church addresses with the local Geolonia checkout and checkpoint quality results"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    dependsOn(prepareChurchGeoNames)
    args(
        "normalize-addresses",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--normalizer-dir",
        geoloniaNormalizerDirectory.get(),
        "--concurrency",
        providers.gradleProperty("addressNormalizationConcurrency").orElse("4").get(),
    )
}

tasks.named("buildSearchSnapshot") {
    dependsOn(normalizeChurchAddresses)
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

    // following line does not work in CI it is speciic to joel's dev box so commenting out. codex, if you read this comment and you think it is ok, delete the commented following commented out code:
    /*doFirst {
        val exit = ProcessBuilder("df", "-h", "/media/joel/llms").inheritIO().start().waitFor()
        check(exit == 0) { "df failed; refusing to invoke Ollama" }
    }*/
}

tasks.register<JavaExec>("fetchUrl") {
    group = "crossmap"
    description = "Fetch a single URL through the full fetch pipeline (HttpClient → LightPanda → Playwright) with verbose logging"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "fetch-url",
        "--url",
        providers.gradleProperty("url").orElse("").get(),
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
}

tasks.register<JavaExec>("validateChineseDictionaries") {
    group = "crossmap"
    description = "Validate paired zh-Hans/zh-Hant church-name dictionaries and write a review report"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "validate-chinese-dictionaries",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
}

tasks.register<JavaExec>("dryRunChineseLocalizedNames") {
    group = "crossmap"
    description = "Preview Chinese church/minister localization and produce review reports without changing the catalog"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "localize-chinese-names",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--dry-run",
    )
    dependsOn("validateChineseDictionaries")
}

tasks.register<JavaExec>("generateChineseLocalizedNames") {
    group = "crossmap"
    description = "Idempotently generate Chinese church/minister names while preserving official and reviewed values"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "localize-chinese-names",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
    dependsOn("validateChineseDictionaries")
}

tasks.register("produceChineseReviewReport") {
    group = "crossmap"
    description = "Produce machine-readable and human-readable Chinese localization review reports"
    dependsOn("dryRunChineseLocalizedNames")
}

tasks.register<JavaExec>("dryRunVietnameseLocalizedNames") {
    group = "crossmap"
    description = "Preview Vietnamese church/minister localization and produce review reports without changing the catalog"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "localize-vietnamese-names",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
        "--dry-run",
    )
}

tasks.register<JavaExec>("generateVietnameseLocalizedNames") {
    group = "crossmap"
    description = "Idempotently generate Vietnamese church/minister names while preserving official and reviewed values"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.crawl.MainKt"
    workingDir = rootProject.projectDir
    args(
        "localize-vietnamese-names",
        "--resources",
        providers.gradleProperty("crossmapResources").orElse("resources").get(),
    )
}

tasks.register("produceVietnameseReviewReport") {
    group = "crossmap"
    description = "Produce machine-readable and human-readable Vietnamese localization review reports"
    dependsOn("dryRunVietnameseLocalizedNames")
}

tasks.register("reindexVietnameseFields") {
    group = "crossmap"
    description = "Rebuild the reproducible search snapshot including VietnameseAnalyzer fields"
    dependsOn("buildSearchSnapshot")
}

tasks.register("reindexChineseFields") {
    group = "crossmap"
    description = "Rebuild the reproducible search snapshot including Chinese script-specific and canonical fields"
    dependsOn("buildSearchSnapshot")
}

tasks.register<Test>("chineseGoldenTest") {
    group = "verification"
    description = "Run Chinese church-name golden fixtures"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn("testClasses")
    useJUnitPlatform()
    filter {
        includeTestsMatching("jp.co.crossmap.crawl.ChineseChurchNameGoldenTest")
    }
}

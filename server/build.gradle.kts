plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "jp.co.crossmap"
version = "1.0.0"

kotlin {
    jvmToolchain(24)
}

application {
    mainClass = "jp.co.crossmap.ApplicationKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // Ktor serves the materialized snapshot/static output and never requires a live Neo4j connection.
    dependsOn(":crawl:buildSearchSnapshot")
}

tasks.register<JavaExec>("runCurrentIndex") {
    group = "crossmap"
    description = "Start the server immediately with the current schema-compatible latest search snapshot"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.ApplicationKt"
    workingDir = rootProject.projectDir
}

tasks.test {
    systemProperty("crossmap.project.root", rootProject.projectDir.absolutePath)
    // The real catalog now carries seven localized names per church; static-site tests render
    // all 66k locale variants and need more than Gradle's default 512 MiB test-worker heap.
    maxHeapSize = "2g"
}

val lightpandaE2eTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run the real index -> browser results -> church detail flow with Lightpanda"
    dependsOn(":crawl:buildSearchSnapshot", "generateChurchPages")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("jp.co.crossmap.LightPandaSearchE2ETest")
    }
    environment("CROSSMAP_LIGHTPANDA_E2E", "1")
    systemProperty("crossmap.project.root", rootProject.projectDir.absolutePath)
    shouldRunAfter(tasks.test)
}

tasks.register<JavaExec>("generateChurchPages") {
    group = "crossmap"
    description = "Generate localized static pages from bounded Neo4j church-detail projections"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.StaticSiteGeneratorCli"
    workingDir = rootProject.projectDir
    // Static rendering is deliberately read-only. Running dataCleanup here used to rewrite
    // churches.json after an index had been published, which made runCurrentIndex reject it.
    dependsOn(":crawl:prepareGeoNameCache")
    val denominationEnglishNames = providers.gradleProperty("denominationEnglishNames").orElse("resources/catalog/denomination-en-names.json")
    val churchPageOutput = providers.gradleProperty("churchPageOutput").orElse("webclient")
    val geonameEnglishLexicon = providers.gradleProperty("geonameEnglishLexicon").orElse("cache/geoname/japan/church-name-lexicon.json")
    val i18nDirectory = providers.gradleProperty("i18nDirectory").orElse("resources/i18n")
    val siteBaseUrl = providers.gradleProperty("crossmapSiteBaseUrl").orElse("https://www.crossmap.co.jp")
    val staticSiteParallelism = providers.gradleProperty("crossmapStaticSiteParallelism")
        .orElse(Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toString())
    inputs.files(rootProject.fileTree("resources/catalog") { include("denomination-*-names.json") })
    inputs.dir(rootProject.layout.projectDirectory.dir(i18nDirectory.get()))
    inputs.files(rootProject.fileTree("server/src/main/resources") { include("index.html", "result.html", "church.html") })
    inputs.property("siteBaseUrl", siteBaseUrl)
    inputs.property("parallelism", staticSiteParallelism)
    outputs.dir(rootProject.layout.projectDirectory.dir(churchPageOutput.get()))
    outputs.upToDateWhen { false }
    args(
        denominationEnglishNames.get(),
        churchPageOutput.get(),
        geonameEnglishLexicon.get(),
        i18nDirectory.get(),
        siteBaseUrl.get(),
        staticSiteParallelism.get(),
    )
}

tasks.register<JavaExec>("validateI18n") {
    group = "verification"
    description = "Validate all canonical UI message catalogs"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.I18nValidatorCli"
    workingDir = rootProject.projectDir
    val i18nDirectory = providers.gradleProperty("i18nDirectory").orElse("resources/i18n")
    inputs.dir(rootProject.layout.projectDirectory.dir(i18nDirectory.get()))
    args(i18nDirectory.get())
}

dependencies {
    api(projects.core)
    // Used by the build-time StaticSiteGeneratorCli only; Application.module does not open Neo4j.
    implementation(projects.catalog)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serializationJson)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
    implementation(libs.freemarker)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}

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
    dependsOn(":crawl:buildSearchSnapshot", "generateChurchPages")
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
    description = "Generate static English-name church detail pages"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.StaticSiteGeneratorCli"
    workingDir = rootProject.projectDir
    dependsOn(":crawl:dataCleanup", ":crawl:prepareGeoNameCache")
    val churchCatalog = providers.gradleProperty("churchCatalog").orElse("resources/catalog/churches.json")
    val denominationEnglishNames = providers.gradleProperty("denominationEnglishNames").orElse("resources/catalog/denomination-en-names.json")
    val churchPageOutput = providers.gradleProperty("churchPageOutput").orElse("webclient")
    val geonameEnglishLexicon = providers.gradleProperty("geonameEnglishLexicon").orElse("cache/geoname/japan/church-name-lexicon.json")
    val i18nDirectory = providers.gradleProperty("i18nDirectory").orElse("resources/i18n")
    val siteBaseUrl = providers.gradleProperty("crossmapSiteBaseUrl").orElse("https://www.crossmap.co.jp")
    inputs.file(rootProject.layout.projectDirectory.file(churchCatalog.get()))
    inputs.files(rootProject.fileTree("resources/catalog") { include("denomination-*-names.json") })
    inputs.dir(rootProject.layout.projectDirectory.dir(i18nDirectory.get()))
    inputs.files(rootProject.fileTree("server/src/main/resources") { include("index.html", "result.html", "church.html") })
    inputs.property("siteBaseUrl", siteBaseUrl)
    outputs.dir(rootProject.layout.projectDirectory.dir(churchPageOutput.get()))
    args(
        churchCatalog.get(),
        denominationEnglishNames.get(),
        churchPageOutput.get(),
        geonameEnglishLexicon.get(),
        i18nDirectory.get(),
        siteBaseUrl.get(),
    )
}

tasks.register<JavaExec>("validateI18n") {
    group = "verification"
    description = "Validate the five canonical UI message catalogs"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.I18nValidatorCli"
    workingDir = rootProject.projectDir
    val i18nDirectory = providers.gradleProperty("i18nDirectory").orElse("resources/i18n")
    inputs.dir(rootProject.layout.projectDirectory.dir(i18nDirectory.get()))
    args(i18nDirectory.get())
}

dependencies {
    api(projects.core)
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

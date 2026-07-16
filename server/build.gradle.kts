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
    dependsOn(":crawl:dataCleanup", ":crawl:populateDenominationEnglishNames", ":crawl:prepareGeoNameCache")
    args(
        providers.gradleProperty("churchCatalog").orElse("resources/catalog/churches.json").get(),
        providers.gradleProperty("denominationEnglishNames").orElse("resources/catalog/denomination-en-names.json").get(),
        providers.gradleProperty("churchPageOutput").orElse("webclient/church").get(),
        providers.gradleProperty("geonameEnglishLexicon").orElse("cache/geoname/japan/church-name-lexicon.json").get(),
    )
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

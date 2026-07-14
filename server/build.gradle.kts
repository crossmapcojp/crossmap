plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "jp.co.crossmap"
version = "1.0.0"
application {
    mainClass = "jp.co.crossmap.ApplicationKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    systemProperty("crossmap.project.root", rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("generateChurchPages") {
    group = "crossmap"
    description = "Generate static English-name church detail pages"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "jp.co.crossmap.StaticSiteGeneratorCli"
    workingDir = rootProject.projectDir
    dependsOn(":crawl:dataCleanup", ":crawl:populateDenominationEnglishNames")
    args(
        providers.gradleProperty("churchCatalog").orElse("resources/catalog/churches.json").get(),
        providers.gradleProperty("denominationEnglishNames").orElse("resources/catalog/denomination-english-names.json").get(),
        providers.gradleProperty("churchPageOutput").orElse("webclient/church").get(),
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

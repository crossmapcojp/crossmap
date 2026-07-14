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

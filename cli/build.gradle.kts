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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "jp.co.crossmap.cli.MainKt"
    applicationName = "cm"
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
}

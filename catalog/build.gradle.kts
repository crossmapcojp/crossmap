plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "jp.co.crossmap"
version = "1.0.0"

kotlin {
    jvmToolchain(24)
}

dependencies {
    api(projects.core)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.neo4j.java.driver)
    testImplementation(libs.kotlin.testJunit)
}

tasks.test {
    useJUnit()
}

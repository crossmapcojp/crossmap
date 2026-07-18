import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    androidLibrary {
        namespace = "jp.co.crossmap.core"
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
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.lucene.kmp.core)
            implementation(libs.lucene.kmp.queryparser)
            implementation(libs.lucene.kmp.analysis.common)
            implementation(libs.lucene.kmp.analysis.kuromoji)
            implementation(libs.lucene.kmp.analysis.nori)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

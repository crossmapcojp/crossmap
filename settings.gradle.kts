rootProject.name = "crossmap"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
    }
}

val useLocalLuceneKmp = providers.gradleProperty("useLocalLuceneKmp").orNull == "true" ||
    providers.environmentVariable("USE_LOCAL_LUCENE_KMP").orNull == "true"

if (useLocalLuceneKmp && file("../bbl-lucene/lucene-kmp").isDirectory) {
    logger.lifecycle("Using sibling lucene-kmp composite build")
    includeBuild("../bbl-lucene/lucene-kmp") {
        dependencySubstitution {
            substitute(module("org.gnit.lucene-kmp:lucene-kmp-core")).using(project(":core"))
            substitute(module("org.gnit.lucene-kmp:lucene-kmp-queryparser")).using(project(":queryparser"))
            substitute(module("org.gnit.lucene-kmp:lucene-kmp-analysis-common")).using(project(":analysis:common"))
            substitute(module("org.gnit.lucene-kmp:lucene-kmp-analysis-extra")).using(project(":analysis:extra"))
            substitute(module("org.gnit.lucene-kmp:lucene-kmp-analysis-kuromoji")).using(project(":analysis:kuromoji"))
            substitute(module("org.gnit.lucene-kmp:lucene-kmp-analysis-smartcn")).using(project(":analysis:smartcn"))
        }
    }
}

include(":app:androidApp")
include(":app:shared")
include(":core")
include(":catalog")
include(":server")
include("cli")
include("crawl")

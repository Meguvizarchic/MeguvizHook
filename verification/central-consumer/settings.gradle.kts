pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.4.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
    }
}

val centralRepo = providers.gradleProperty("centralRepo").orNull
    ?: error("Set -PcentralRepo to the staging Maven repository URI")
val metadataMode = providers.gradleProperty("metadataMode").orElse("gradle").get()
require(metadataMode in setOf("gradle", "pom")) { "metadataMode must be gradle or pom" }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "candidateStaging"
                    url = uri(centralRepo)
                    metadataSources {
                        if (metadataMode == "gradle") {
                            // No POM fallback: the candidate must actually provide .module metadata.
                            gradleMetadata()
                        } else {
                            mavenPom()
                            // A POM marker must not redirect this independent run to .module metadata.
                            ignoreGradleMetadataRedirection()
                        }
                    }
                }
            }
            filter { includeGroup("io.github.jichuo1.meguviz.hook") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "central-consumer-verification"
include(":jvm", ":backend101", ":backend102", ":refbridge")

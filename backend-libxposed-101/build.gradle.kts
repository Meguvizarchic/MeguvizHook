plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.backend.libxposed101"
    compileSdk {
        version = release(providers.gradleProperty("project.android.compileSdk").get().toInt()) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = providers.gradleProperty("project.android.minSdk").get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    api(project(":hook-api"))
    api(project(":hook-core"))

    // API 101 is a separate compileOnly input; the 102 artifact is never on this module's classpath.
    compileOnly(libs.libxposed101.api)

    testImplementation(libs.junit)
    testImplementation(project(":hook-testing"))
    testImplementation(libs.libxposed101.api)
}

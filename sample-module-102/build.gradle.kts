plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.sample102"
    compileSdk {
        version = release(providers.gradleProperty("project.android.compileSdk").get().toInt()) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.hook.sample102"
        minSdk = providers.gradleProperty("project.android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("project.android.targetSdk").get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // AGP 9 built-in Kotlin adds stdlib metadata even to a Java-only module. This sample is
            // deliberately Java-only, so the metadata is excluded rather than silently shipped.
            excludes += setOf("kotlin/**", "META-INF/*.kotlin_module")
        }
    }
}

configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    implementation(project(":hook-api"))
    implementation(project(":hook-core"))
    implementation(project(":backend-libxposed-102"))

    // Exactly one backend generation in this variant. The framework API is compileOnly and must not
    // appear in the APK; adding backend-libxposed-101 here later must stay mutually exclusive.
    compileOnly(libs.libxposed.api)
}

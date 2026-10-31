plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.sample101"
    compileSdk {
        version = release(providers.gradleProperty("project.android.compileSdk").get().toInt()) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.hook.sample101"
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
            excludes += setOf("kotlin/**", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation(project(":hook-api"))
    implementation(project(":hook-core"))
    implementation(project(":backend-libxposed-101"))

    // Exactly one backend generation. Framework classes are provided by the 101 runtime.
    compileOnly(libs.libxposed101.api)
}

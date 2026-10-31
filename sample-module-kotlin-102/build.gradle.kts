import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.sample.kotlin102"
    compileSdk {
        version = release(providers.gradleProperty("project.android.compileSdk").get().toInt()) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.hook.sample.kotlin102"
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
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":hook-dsl"))
    implementation(project(":backend-libxposed-102"))

    // Exactly one backend generation. Framework classes are provided by the 102 runtime.
    compileOnly(libs.libxposed.api)
}

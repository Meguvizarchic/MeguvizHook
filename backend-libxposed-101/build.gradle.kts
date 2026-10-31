plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.backend.libxposed101"
    compileSdk = providers.gradleProperty("project.android.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("project.android.minSdk").get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":hook-api"))
    api(project(":hook-core"))

    // API 101 is a separate compileOnly input; the 102 artifact is never on this module's classpath.
    compileOnly(libs.libxposed101.api)
    compileOnly(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(project(":hook-testing"))
    testImplementation(libs.libxposed101.api)
    testImplementation(libs.androidx.annotation)
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.backend.libxposed102"
    compileSdk = providers.gradleProperty("project.android.compileSdk").get().toInt()

    defaultConfig {
        minSdk = providers.gradleProperty("project.android.minSdk").get().toInt()
    }

    compileOptions {
        // Same language level as BIL's Android modules; hook-api/hook-core stay at Java 8 bytecode.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":hook-api"))
    api(project(":hook-core"))

    // Framework classes are provided by the runtime and must never be packaged into a consumer APK.
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(project(":hook-testing"))
    // compileOnly never reaches the unit-test classpath; the stub-based mapping fixture needs the
    // real 102 interface definitions, matching how BIL consumes them in its own unit tests.
    testImplementation(libs.libxposed.api)
    testImplementation(libs.androidx.annotation)
}

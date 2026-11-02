plugins { alias(libs.plugins.android.application) }

android {
    namespace = "io.github.jichuo1.meguviz.hook.cpubodyhost"
    compileSdk { version = release(providers.gradleProperty("project.android.compileSdk").get().toInt()) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.jichuo1.meguviz.hook.cpubodyhost"
        minSdk = providers.gradleProperty("project.android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("project.android.targetSdk").get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("deviceTest") { initWith(getByName("release")); signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += listOf("release") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources { excludes += setOf("kotlin/**", "META-INF/*.kotlin_module") } }
}
configurations.matching { it.name == "releaseRuntimeClasspath" || it.name == "deviceTestRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
dependencies {

    testImplementation(libs.junit)
}

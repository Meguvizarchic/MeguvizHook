import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins { id("com.android.application") }

val candidateVersion = providers.gradleProperty("centralVersion").orNull
    ?: error("Set -PcentralVersion to the exact staged version")
val candidateGroup = "io.github.jichuo1.meguviz.hook"

android {
    namespace = "consumer.backend101"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "consumer.backend101"
        minSdk = 27
        targetSdk = 37
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // API/core must arrive through the published backend rather than project dependencies.
    implementation("$candidateGroup:backend-libxposed-101:$candidateVersion")
    compileOnly("io.github.libxposed:api:101.0.0")
}

tasks.register("verifyResolvedDependencies") {
    doLast {
        fun resolved(name: String) = configurations.getByName(name).incoming.resolutionResult
            .allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
        val runtime = resolved("debugRuntimeClasspath")
        val ours = runtime.filter { it.group == candidateGroup }.associate { it.module to it.version }
        val expected = setOf("hook-api", "hook-core", "backend-libxposed-101")
        check(ours.keys == expected && ours.values.all { it == candidateVersion }) {
            "API101 dependency graph differs from the staged graph: $ours"
        }
        check(runtime.none { it.group == "io.github.libxposed" }) { "framework leaked into API101 runtime" }
        check(resolved("debugCompileClasspath").any {
            it.group == "io.github.libxposed" && it.module == "api" && it.version == "101.0.0"
        }) { "API101 compileOnly framework is missing" }
        println("ANDROID_101_DEPENDENCIES_OK framework_runtime=false")
    }
}

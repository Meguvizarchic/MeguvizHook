import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins { id("com.android.application") }

val candidateVersion = providers.gradleProperty("centralVersion").orNull
    ?: error("Set -PcentralVersion to the exact staged version")
val candidateGroup = "io.github.jichuo1.meguviz.hook"

android {
    namespace = "consumer.refbridge"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "consumer.refbridge"
        minSdk = 27
        targetSdk = 37
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The core, API and Ref library must come from published transitive metadata.
    implementation("$candidateGroup:hook-ref-bridge:$candidateVersion")
}

tasks.register("verifyResolvedDependencies") {
    doLast {
        val modules = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult
            .allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
        val ours = modules.filter { it.group == candidateGroup }.associate { it.module to it.version }
        val expected = setOf("hook-api", "hook-core", "hook-ref-bridge")
        check(ours.keys == expected && ours.values.all { it == candidateVersion }) {
            "Ref bridge dependency graph differs from the staged graph: $ours"
        }
        val refs = modules.filter { it.group == "io.github.jichuo1.meguviz" }
            .associate { it.module to it.version }
        check(refs == mapOf("meguviz-ref-core" to "1.0.0")) { "unexpected Ref dependency graph: $refs" }
        check(modules.none { it.group == "io.github.libxposed" }) { "framework leaked into Ref bridge runtime" }
        println("ANDROID_REF_DEPENDENCIES_OK framework_runtime=false")
    }
}

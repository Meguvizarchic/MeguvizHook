import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val candidateVersion = providers.gradleProperty("centralVersion").orNull
    ?: error("Set -PcentralVersion to the exact staged version")
val candidateGroup = "io.github.jichuo1.meguviz.hook"

dependencies {
    // Deliberately omit direct hook-api and hook-core dependencies: these must be transitive.
    implementation("$candidateGroup:hook-dsl:$candidateVersion")
    implementation("$candidateGroup:hook-testing:$candidateVersion")
    implementation("$candidateGroup:hook-ref-bridge:$candidateVersion")
}

application { mainClass.set("consumer.ConsumerKt") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.register("verifyResolvedDependencies") {
    doLast {
        val modules = configurations.getByName("runtimeClasspath").incoming.resolutionResult
            .allComponents.mapNotNull { it.id as? ModuleComponentIdentifier }
        val ours = modules.filter { it.group == candidateGroup }.associate { it.module to it.version }
        val expected = setOf("hook-api", "hook-core", "hook-dsl", "hook-testing", "hook-ref-bridge")
        check(ours.keys == expected && ours.values.all { it == candidateVersion }) {
            "JVM dependency graph differs from the staged graph: $ours"
        }
        check(modules.none { it.group == "io.github.libxposed" }) { "framework leaked into JVM runtime" }
        val refs = modules.filter { it.group == "io.github.jichuo1.meguviz" }
            .associate { it.module to it.version }
        check(refs == mapOf("meguviz-ref-core" to "1.0.0")) { "unexpected Ref dependency graph: $refs" }
        println("JVM_DEPENDENCIES_OK modules=${ours.keys.sorted()}")
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":hook-dsl"))
    // Intentionally no direct api/core dependency: this consumer verifies their public exposure by hook-dsl.
    implementation(project(":hook-testing"))
}

extensions.configure<JavaApplication> {
    mainClass = "io.github.jichuo1.meguviz.hook.sample.kotlin.KotlinContractsSampleKt"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks.named<JavaExec>("run") {
    standardOutput = System.out
    errorOutput = System.err
}

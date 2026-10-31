plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    group = "io.github.jichuo1.meguviz.hook"
    version = "0.1.0-SNAPSHOT"
}

// Shared configuration for the pure JVM modules. No Android, framework, Binder, JNI or Rust types
// are allowed on these compile classpaths; the backend adapters depend on them, never the reverse.
configure(listOf(project(":hook-api"), project(":hook-core"), project(":hook-dsl"), project(":hook-testing"), project(":sample-java-contracts"), project(":sample-kotlin-contracts"))) {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        // Java 8 bytecode keeps the core consumable from Android minSdk 27 without desugaring flags.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

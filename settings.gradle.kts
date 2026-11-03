pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Legacy Xposed repository kept for the future API 101 adapter only; nothing resolves from it in this slice.
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "MeguvizHook"

// Only modules with real content in this slice. No empty service/dsl/native modules are created up front.
include(":hook-api")
include(":hook-core")
include(":hook-dsl")
include(":hook-testing")
include(":hook-ref-bridge")
// JVM microbenchmarks only; not published and not device evidence.
include(":benchmark-jvm")
include(":sample-java-contracts")
include(":sample-kotlin-contracts")
include(":backend-libxposed-101")
include(":backend-libxposed-102")
include(":sample-module-101")
include(":sample-module-102")
include(":sample-module-kotlin-102")
include(":device-test-host")
include(":device-test-module-101")
include(":device-test-module-102")
include(":device-benchmark-host")
include(":device-benchmark-module-101")
include(":device-benchmark-module-102")
include(":device-clock-diagnostic")
include(":device-cpubody-host")
include(":device-cpubody-module-101")
include(":device-cpubody-module-102")

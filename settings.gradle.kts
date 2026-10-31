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
include(":hook-testing")
include(":sample-java-contracts")
include(":backend-libxposed-101")
include(":backend-libxposed-102")
include(":sample-module-101")
include(":sample-module-102")

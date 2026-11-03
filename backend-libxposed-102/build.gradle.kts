plugins {
    alias(libs.plugins.android.library)
}

abstract class PrepareLegalResources : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val legalFiles: ConfigurableFileCollection
    @get:Input abstract val moduleName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val target = outputDirectory.dir("META-INF").get().asFile
        target.mkdirs()
        legalFiles.files.forEach { it.copyTo(target.resolve("${it.name}-MeguvizHook-${moduleName.get()}"), overwrite = true) }
    }
}

val legalResources = tasks.register<PrepareLegalResources>("prepareLegalResources") {
    legalFiles.from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    moduleName.set(project.name)
    outputDirectory.set(layout.buildDirectory.dir("generated/meguvizLegal"))
}

android {
    namespace = "io.github.jichuo1.meguviz.hook.backend.libxposed102"
    enableKotlin = false
    compileSdk {
        version = release(providers.gradleProperty("project.android.compileSdk").get().toInt()) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = providers.gradleProperty("project.android.minSdk").get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // Same language level as BIL's Android modules; hook-api/hook-core stay at Java 8 bytecode.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.resources?.addGeneratedSourceDirectory(legalResources, PrepareLegalResources::outputDirectory)
}

tasks.register<Javadoc>("centralJavadoc") {
    source(fileTree("src/main/java") { include("**/*.java") })
    val releaseCompile = tasks.named<JavaCompile>("compileReleaseJavaWithJavac")
    dependsOn(releaseCompile)
    // Javac's classpath contains AGP's transformed AAR class jars, not raw AAR files.
    classpath = files(androidComponents.sdkComponents.bootClasspath) + releaseCompile.get().classpath
}

configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    api(project(":hook-api"))
    api(project(":hook-core"))

    // Framework classes are provided by the runtime and must never be packaged into a consumer APK.
    compileOnly(libs.libxposed.api)

    testImplementation(libs.junit)
    testImplementation(project(":hook-testing"))
    // compileOnly never reaches the unit-test classpath; the stub-based mapping fixture needs the
    // real 102 interface definitions, matching how BIL consumes them in its own unit tests.
    testImplementation(libs.libxposed.api)
}

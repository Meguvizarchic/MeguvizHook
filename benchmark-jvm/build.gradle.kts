// JVM-only microbenchmarks. Not published, not part of apiCheck, and not evidence for ART or
// device performance. Run explicitly: ./gradlew :benchmark-jvm:jmh -Pjmh.include=<regex>
plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":hook-core"))
    implementation(project(":hook-testing"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
}

val jmhInclude = providers.gradleProperty("jmh.include").orElse(".*")
val jmhArgs = providers.gradleProperty("jmh.args").orElse("-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc")
val jmhResults = layout.buildDirectory.file("reports/jmh/results.json")

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH microbenchmarks on the host JVM (not ART evidence)."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val include = jmhInclude
    val extra = jmhArgs
    val results = jmhResults
    argumentProviders.add(CommandLineArgumentProvider {
        val output = results.get().asFile
        output.parentFile.mkdirs()
        listOf(include.get()) + extra.get().split(' ').filter { it.isNotBlank() } +
            listOf("-rf", "json", "-rff", output.absolutePath)
    })
}

plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
}

dependencies {
    api(project(":hook-core"))
    api(libs.meguviz.ref.core)
    testImplementation(project(":hook-testing"))
    testImplementation(libs.junit)
}

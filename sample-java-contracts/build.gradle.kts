apply(plugin = "application")

dependencies {
    implementation(project(":hook-api"))
    implementation(project(":hook-core"))
    // The sample consumes the fake backend on purpose: it demonstrates Java-only contracts on a
    // plain JVM, without Android, Kotlin, a framework or a device.
    implementation(project(":hook-testing"))
}

extensions.configure<JavaApplication> {
    mainClass = "io.github.jichuo1.meguviz.hook.sample.java.JavaContractsSample"
}

tasks.named<JavaExec>("run") {
    standardOutput = System.out
    errorOutput = System.err
}

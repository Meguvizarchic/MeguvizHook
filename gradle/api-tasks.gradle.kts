import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

abstract class ApiSurfaceDump : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jarFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun dump() {
        val jar = jarFile.get().asFile
        val classNames = ZipFile(jar).use { archive ->
            archive.entries().toList()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .sorted()
        }
        require(classNames.isNotEmpty()) { "No classes found in ${jar.name}" }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        val javaExecutable = File(
            System.getProperty("java.home"),
            "bin/java${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}",
        )
        report.outputStream().use { output ->
            execOperations.exec {
                executable = javaExecutable.absolutePath
                args("--module", "jdk.jdeps/com.sun.tools.javap.Main")
                args("-public", "-classpath", jar.absolutePath)
                args(classNames)
                standardOutput = output
            }
        }
    }
}

val apiModules = listOf("hook-api", "hook-core", "hook-dsl")
val aggregateDumpTasks = mutableListOf<String>()
val aggregateCheckTasks = mutableListOf<String>()

apiModules.forEach { moduleName ->
    val module = project(":$moduleName")
    val baseline = rootProject.layout.projectDirectory.file("work/hook/api_dump_$moduleName.txt")
    val currentReport = module.layout.buildDirectory.file("reports/api/api_dump_$moduleName.txt")

    val dumpForCheck = module.tasks.register<ApiSurfaceDump>("apiDumpForCheck") {
        group = "verification"
        description = "Generates the current public API surface for $moduleName."
        dependsOn(module.tasks.named("jar"))
        jarFile.set(module.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java).flatMap { it.archiveFile })
        reportFile.set(currentReport)
    }

    module.tasks.register<ApiSurfaceDump>("apiDump") {
        group = "verification"
        description = "Updates the committed public API surface baseline for $moduleName."
        dependsOn(module.tasks.named("jar"))
        jarFile.set(module.tasks.named("jar", org.gradle.api.tasks.bundling.Jar::class.java).flatMap { it.archiveFile })
        reportFile.set(baseline)
    }

    module.tasks.register("apiCheck") {
        group = "verification"
        description = "Checks $moduleName public API surface against its committed baseline."
        dependsOn(dumpForCheck)
        inputs.file(baseline).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.upToDateWhen { false }
        doLast {
            val expected = baseline.asFile.readText()
            val actual = currentReport.get().asFile.readText()
            if (expected != actual) {
                throw GradleException(
                    "Public API surface changed for $moduleName. " +
                        "Review the change, then run ./gradlew :$moduleName:apiDump to update the baseline.",
                )
            }
        }
    }

    aggregateDumpTasks += ":$moduleName:apiDump"
    aggregateCheckTasks += ":$moduleName:apiCheck"
}

tasks.register("apiDump") {
    group = "verification"
    description = "Updates all committed public API surface baselines."
    dependsOn(aggregateDumpTasks)
}

tasks.register("apiCheck") {
    group = "verification"
    description = "Checks all public API surface baselines."
    dependsOn(aggregateCheckTasks)
}

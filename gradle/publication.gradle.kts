import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.plugins.signing.SigningExtension

// Publishing is opt-in. No remote repository, credentials, or upload task is configured here.
// A Central candidate is generated into a caller-selected local Maven-layout directory.
val publishableProjects = listOf(
    "hook-api", "hook-core", "hook-dsl", "hook-testing", "hook-ref-bridge",
    "backend-libxposed-101", "backend-libxposed-102"
)

configure(publishableProjects.map { project(":$it") }) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    val androidLibrary = name.startsWith("backend-libxposed-")
    val moduleName = name
    val signed = providers.gradleProperty("signPublications").map { value ->
        require(value == "true" || value == "false") { "signPublications must be true or false" }
        value == "true"
    }.orElse(false)
    tasks.withType<Jar>().configureEach {
        // Distinct names allow several MeguvizHook modules in one consumer without resource clashes.
        from(rootProject.file("LICENSE")) {
            into("META-INF")
            rename { "LICENSE-MeguvizHook-$moduleName" }
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
            rename { "NOTICE-MeguvizHook-$moduleName" }
        }
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "centralStaging"
                val target = providers.gradleProperty("centralStagingDir")
                    .map { rootProject.file(it) }
                    .orElse(rootProject.layout.buildDirectory.dir("central-staging").map { it.asFile })
                url = target.get().toURI()
            }
        }
    }

    afterEvaluate {
        if (moduleName == "hook-dsl") {
            // Kotlin and Java plugins otherwise produce the same *-sources.jar concurrently.
            // The Java sourcesJar already includes the Kotlin source set; keep one owner.
            tasks.named("kotlinSourcesJar") { enabled = false }
        }
        val docs = tasks.named<Javadoc>(if (androidLibrary) "centralJavadoc" else "javadoc")
        val dslOverview = if (name == "hook-dsl") {
            val output = layout.buildDirectory.file("central-docs/dsl/index.html")
            tasks.register("centralDslOverview") {
                outputs.file(output)
                doLast {
                    val file = output.get().asFile
                    file.parentFile.mkdirs()
                    file.writeText("""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>MeguvizHook Kotlin DSL</title></head><body><h1>MeguvizHook Kotlin DSL</h1><p>This archive includes usage and callback contract guides. It does not contain a generated per-symbol Kotlin API reference.</p><p>See the project README and guides for usage.</p></body></html>""", Charsets.UTF_8)
                }
            }
        } else null
        val docsJar = tasks.register<Jar>("centralJavadocJar") {
            archiveClassifier.set("javadoc")
            from(docs)
            if (dslOverview != null) from(dslOverview)
            // The Kotlin DSL has no Java sources; include its public usage and contract guides too.
            from(rootProject.file("README.md")) { into("guides") }
            from(rootProject.file("docs/contracts/callback_contract.md")) { into("guides") }
        }
        val publishing = extensions.getByType<PublishingExtension>()
        val publication = publishing.publications.create("maven", MavenPublication::class.java) {
            if (!androidLibrary) from(components["java"])
            artifact(docsJar)
            pom {
                name.set("MeguvizHook ${project.name}")
                description.set("Android Hook SDK: ${project.name} library")
                url.set("https://github.com/Meguvizarchic/MeguvizHook")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("jichuo1")
                        name.set("MeguvizHook contributors")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Meguvizarchic/MeguvizHook.git")
                    developerConnection.set("scm:git:https://github.com/Meguvizarchic/MeguvizHook.git")
                    url.set("https://github.com/Meguvizarchic/MeguvizHook")
                }
            }
        }
        if (androidLibrary) {
            // AGP creates the release component later than the root script's afterEvaluate hook.
            components.matching { it.name == "release" }.all { publication.from(this) }
        }
        if (signed.get()) {
            extensions.configure<SigningExtension> {
                // Signing material stays under the caller's GPG/agent control. Never pass the
                // secret through Gradle properties or serialize it into configuration cache.
                useGpgCmd()
                isRequired = true
                sign(publication)
            }
        }
    }
}

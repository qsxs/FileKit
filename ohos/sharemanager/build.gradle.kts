import groovy.json.JsonOutput
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import java.security.MessageDigest

plugins {
    kotlin("multiplatform") version "2.4.21-0.1.0-13"
    kotlin("plugin.compose") version "2.4.21-0.1.0-13"
    id("org.jetbrains.compose") version "1.12.0-0.1.0-23"
    `maven-publish`
}

group = "io.github.qsxs.cpf.filekit"
val sourceCommit = providers.gradleProperty("sourceCommit").get()
require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "Expected the full FileKit source commit" }
version = "0.16.0-cpf-sharemanager-${sourceCommit.take(12)}"

val composeVersion = "1.12.0-0.1.0-23"
val coreVersion = "0.16.0-cpf11-preview-22683094fd5d-40864370116-1"
val forkRoot = rootDir.resolve("../..").canonicalFile
val sourceRoot = forkRoot.resolve("filekit-dialogs/src")
val sourceDirectories = listOf("commonMain", "nonWebMain", "mobileMain", "ohosMain", "ohosArm64Main", "ohosX64Main")
    .map { sourceRoot.resolve("$it/kotlin") }
val communityLicense = forkRoot.resolve("ohos/provenance/vendor/community/LICENSE")
val upstreamLicense = forkRoot.resolve("LICENSE")

kotlin {
    ohosX64()
    ohosArm64()

    sourceSets {
        val commonMain by getting {
            kotlin.setSrcDirs(listOf(sourceRoot.resolve("commonMain/kotlin")))
            dependencies {
                api("io.github.qsxs.cpf.filekit:filekit-core:$coreVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-0.1.0-07")
            }
        }
        val nonWebMain by creating {
            dependsOn(commonMain)
            kotlin.setSrcDirs(listOf(sourceRoot.resolve("nonWebMain/kotlin")))
        }
        val mobileMain by creating {
            dependsOn(nonWebMain)
            kotlin.setSrcDirs(listOf(sourceRoot.resolve("mobileMain/kotlin")))
        }
        val ohosMain by creating {
            dependsOn(mobileMain)
            kotlin.setSrcDirs(listOf(sourceRoot.resolve("ohosMain/kotlin")))
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-adaptive:$composeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0-0.1.0-07")
            }
        }
        listOf("ohosX64Main", "ohosArm64Main").forEach { name ->
            getByName(name) {
                dependsOn(ohosMain)
                kotlin.setSrcDirs(listOf(sourceRoot.resolve("$name/kotlin")))
            }
        }
    }
}

val provenance = layout.buildDirectory.file("publication/provenance.json")
val recordProvenance by tasks.registering {
    inputs.property("sourceCommit", sourceCommit)
    inputs.files(sourceDirectories.map { fileTree(it) { include("**/*.kt") } }, upstreamLicense, communityLicense)
    outputs.file(provenance)
    doLast {
        val files = inputs.files.filter(File::isFile).sortedBy { it.relativeTo(forkRoot).invariantSeparatorsPath }
        val digests = files.associate { file ->
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            file.relativeTo(forkRoot).invariantSeparatorsPath to hash.joinToString("") { "%02x".format(it) }
        }
        val output = provenance.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "repository" to "https://github.com/qsxs/FileKit",
                        "sourceCommit" to sourceCommit,
                        "kotlin" to "2.4.21-0.1.0-13",
                        "compose" to composeVersion,
                        "coreVersion" to coreVersion,
                        "sha256" to digests,
                    ),
                ),
            ) + "\n",
        )
    }
}

publishing {
    repositories {
        maven {
            name = "Staging"
            url = uri(providers.gradleProperty("stagingRepository").get())
        }
    }
    publications.withType<MavenPublication>().configureEach {
        artifact(upstreamLicense) {
            classifier = "upstream-license"
            extension = "txt"
        }
        artifact(communityLicense) {
            classifier = "community-license"
            extension = "txt"
        }
        artifact(provenance) {
            builtBy(recordProvenance)
            classifier = "provenance"
            extension = "json"
        }
        pom {
            name.set("FileKit Dialogs for HarmonyOS")
            description.set("FileKit 0.16.0 dialogs with CPF Compose system sharing")
            url.set("https://github.com/qsxs/FileKit")
            scm { url.set("https://github.com/qsxs/FileKit/tree/$sourceCommit") }
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit/")
                }
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        val destination = file(providers.gradleProperty("stagingRepository").get())
            .resolve(publication.groupId.replace('.', '/'))
            .resolve(publication.artifactId)
            .resolve(publication.version)
        check(!destination.exists()) { "Refusing to overwrite an existing publication: $destination" }
    }
}

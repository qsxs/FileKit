import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") apply false
    kotlin("plugin.serialization") apply false
    kotlin("plugin.compose") apply false
    id("org.jetbrains.compose") apply false
}

val fork = rootDir.resolve("../..").canonicalFile
val sourceCommit = providers.gradleProperty("filekitSourceCommit").get()
require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "A full FileKit source commit is required" }
val publicationVersion = "0.16.0-cpf-unified-${sourceCommit.take(12)}"
val coroutines = "1.11.0-0.1.0-07"
val serialization = "1.11.0-0.1.0-07"
val compose = "1.12.0-0.1.0-23"

subprojects {
    val isCore = name == "filekit-core"
    val sources = fork.resolve("$name/src")
    group = "io.github.qsxs.cpf.filekit"
    version = publicationVersion

    pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    pluginManager.apply("maven-publish")
    if (isCore) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
    } else {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.compose")
    }

    extensions.configure<KotlinMultiplatformExtension> {
        explicitApi()
        ohosX64()
        ohosArm64()

        sourceSets {
            val commonMain = getByName("commonMain") {
                kotlin.setSrcDirs(listOf(sources.resolve("commonMain/kotlin")))
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
                    if (isCore) {
                        api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1-0.1.0-07")
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization")
                    } else {
                        api(project(":filekit-core"))
                    }
                }
            }
            val nonWebMain = create("nonWebMain") {
                dependsOn(commonMain)
                kotlin.setSrcDirs(listOf(sources.resolve("nonWebMain/kotlin")))
                if (isCore) {
                    dependencies {
                        implementation("org.jetbrains.compose.annotation-internal:annotation:1.9.1-0.1.0-11")
                    }
                }
            }
            val platformParent = if (isCore) nonWebMain else create("mobileMain") {
                dependsOn(nonWebMain)
                kotlin.setSrcDirs(listOf(sources.resolve("mobileMain/kotlin")))
            }
            val ohosMain = create("ohosMain") {
                dependsOn(platformParent)
                kotlin.setSrcDirs(listOf(sources.resolve("ohosMain/kotlin")))
                if (!isCore) {
                    dependencies {
                        implementation("org.jetbrains.compose.runtime:runtime:$compose")
                        implementation("org.jetbrains.compose.ui:ui-adaptive:$compose")
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
                    }
                }
            }
            listOf("ohosX64Main", "ohosArm64Main").forEach { sourceSet ->
                getByName(sourceSet) {
                    dependsOn(ohosMain)
                    kotlin.setSrcDirs(listOf(sources.resolve("$sourceSet/kotlin")))
                }
            }
        }
    }

    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set(project.name)
                description.set("FileKit 0.16.0 for HarmonyOS")
            }
        }
    }
    extensions.extraProperties["filekitRepositoryRoot"] = fork
    apply(from = rootDir.resolve("provenance.gradle"))
}

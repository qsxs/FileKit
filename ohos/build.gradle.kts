import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform") apply false
    kotlin("plugin.serialization") apply false
}

fun readMap(path: File) = (JsonSlurper().parse(path) as Map<*, *>).entries.associate { it.key.toString() to it.value }
fun asMap(value: Any?) = (value as Map<*, *>).entries.associate { it.key.toString() to requireNotNull(it.value) }
fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun filesOf(file: File): List<File> = if (file.isDirectory) file.walkTopDown().filter(File::isFile).toList() else listOf(file)
fun records(files: Iterable<File>) = files.filter(File::isFile).distinct().sortedBy { it.path }.map {
    mapOf("path" to it.absolutePath, "sha256" to digest(it), "size" to it.length())
}
fun sourceSets(module: String): List<String> = if (module == "filekit-core")
    listOf("commonMain", "nonWebMain", "ohosMain") else listOf("commonMain", "nonWebMain", "mobileMain", "ohosMain")

val work = file(providers.gradleProperty("portWork").get()).canonicalFile
val repository = file(providers.gradleProperty("portRepository").get()).canonicalFile
val fingerprint = providers.gradleProperty("portFingerprint").get()
val versionName = providers.gradleProperty("portVersion").get()
val config = readMap(file("port-config.json"))
check(gradle.gradleVersion == config["gradle"] && System.getProperty("java.specification.version") == "17")
check(fingerprint.matches(Regex("[0-9a-f]{64}")))
check(versionName.matches(Regex("0\\.16\\.0-cpf11-preview-[0-9a-f]{12}-[1-9][0-9]*-[1-9][0-9]*")))
val manifest = readMap(work.resolve("compiled-source-manifest.json"))
check(manifest["fingerprint"] == fingerprint)
val pins = readMap(file("dependency-pins.json")).mapValues { (_, value) ->
    if (value == "\$portVersion") versionName else value.toString()
}
fun pinned(ga: String) = "$ga:" + pins.getValue(ga)
fun evidence(name: String, value: Any) {
    val destination = work.resolve("evidence/$name.json")
    destination.parentFile.mkdirs()
    check(!destination.exists()) { "Stage evidence already exists: $destination" }
    destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
}
val expectedFiles = (manifest["files"] as List<*>).map(::asMap)
layout.buildDirectory.set(work.resolve("build/root"))

subprojects {
    val module = name
    group = config.getValue("group").toString()
    version = versionName
    layout.buildDirectory.set(work.resolve("build/$module"))
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            ohosX64()
            ohosArm64()
            sourceSets {
                val commonMain by getting {
                    kotlin.setSrcDirs(listOf(work.resolve("sources/$module/commonMain")))
                    dependencies {
                        implementation(pinned("org.jetbrains.kotlinx:kotlinx-coroutines-core"))
                        if (module == "filekit-core") {
                            api(pinned("org.jetbrains.kotlinx:kotlinx-io-core"))
                            implementation(pinned("org.jetbrains.kotlinx:kotlinx-serialization-core"))
                        } else api(project(":filekit-core"))
                    }
                }
                val nonWebMain = create("nonWebMain") {
                    dependsOn(commonMain)
                    kotlin.setSrcDirs(listOf(work.resolve("sources/$module/nonWebMain")))
                    if (module == "filekit-core") dependencies {
                        implementation(pinned("org.jetbrains.compose.annotation-internal:annotation"))
                    }
                }
                val parent = if (module == "filekit-dialogs") create("mobileMain") {
                    dependsOn(nonWebMain)
                    kotlin.setSrcDirs(listOf(work.resolve("sources/$module/mobileMain")))
                } else nonWebMain
                val ohosMain = create("ohosMain") {
                    dependsOn(parent)
                    kotlin.setSrcDirs(listOf(work.resolve("sources/$module/ohosMain")))
                    if (module == "filekit-dialogs") dependencies {
                        implementation(pinned("org.jetbrains.kotlinx:kotlinx-serialization-json"))
                    }
                }
                listOf("ohosX64Main", "ohosArm64Main").forEach { leaf ->
                    getByName(leaf).dependsOn(ohosMain)
                    getByName(leaf).kotlin.setSrcDirs(listOf(work.resolve("sources/$module/$leaf")))
                }
                matching { it.name.endsWith("Test") }.configureEach { kotlin.setSrcDirs(emptyList<String>()) }
            }
        }
    }
    configurations.configureEach {
        val compiled = (name.endsWith("ResolvableDependenciesMetadata") || name.endsWith("CompileKlibraries") ||
            name.endsWith("CompilationDependenciesMetadata") || name == "allSourceSetsCompileDependenciesMetadata") &&
            !name.contains("Test", ignoreCase = true)
        if (compiled) resolutionStrategy.eachDependency {
            val ga = requested.group + ":" + requested.name
            val fixed = pins[ga] ?: error("Unreviewed FileKit dependency: $name $ga:" + requested.version)
            if (requested.version != fixed) useVersion(fixed)
        }
    }
    plugins.withId("maven-publish") {
        val arktsSources = if (module == "filekit-dialogs") tasks.register<Zip>("fileKitArktsSources") {
            from(work.resolve("arkts/FileKitShareFactory.ets"))
            archiveBaseName.set(module)
            archiveVersion.set(versionName)
            archiveClassifier.set("arkts-sources")
            destinationDirectory.set(work.resolve("build/arkts"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
        } else null
        extensions.configure<PublishingExtension> {
            repositories { maven { name = "Port"; url = uri(repository) } }
            publications.withType<MavenPublication>().configureEach {
                artifact(work.resolve("legal/upstream-LICENSE")) { classifier = "upstream-license"; extension = "txt" }
                artifact(work.resolve("legal/community-LICENSE")) { classifier = "community-license"; extension = "txt" }
                artifact(work.resolve("source-manifest.json")) { classifier = "source-manifest"; extension = "json" }
                artifact(work.resolve("provenance.json")) { classifier = "provenance"; extension = "json" }
                if (name == "kotlinMultiplatform" && arktsSources != null) {
                    artifact(arktsSources) { classifier = "arkts-sources"; extension = "zip" }
                }
                pom {
                    name.set(module)
                    description.set("FileKit 0.16.0 OHOS port with upstream and community MIT sources")
                    licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit/") } }
                    scm { url.set("https://github.com/vinceglb/FileKit") }
                }
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        doFirst {
            if (repository.name == "Port") {
                val destination = rootProject.file(providers.gradleProperty("portRepository").get())
                    .resolve(publication.groupId.replace('.', '/')).resolve(publication.artifactId).resolve(publication.version)
                check(!destination.exists()) { "Refusing to overwrite an existing publication: $destination" }
            }
        }
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(work.resolve("legal")) { into("META-INF/licenses") }
        from(work.resolve("source-manifest.json")) { into("META-INF/upstream") }
        from(work.resolve("compiled-source-manifest.json")) { into("META-INF/upstream") }
    }
}

fun resolved(configuration: Configuration): List<Map<String, Any>> = configuration.incoming.artifacts.artifacts.map { artifact ->
    val id = artifact.id.componentIdentifier
    if (id is ModuleComponentIdentifier) {
        check(pins[id.group + ":" + id.module] == id.version) { "Resolved unpinned FileKit dependency: $id" }
    }
    mapOf("configuration" to configuration.name, "component" to id.displayName,
        "attributes" to artifact.variant.attributes.keySet().associate { it.name to artifact.variant.attributes.getAttribute(it).toString() },
        "files" to records(filesOf(artifact.file)))
}

tasks.register("discoverFileKitPort") {
    doLast {
        val rootTasks = listOf("resolveFileKitPort", "recordFileKitMetadata", "recordFileKitX64",
            "recordFileKitArm64", "publishFileKitPort")
        rootTasks.forEach { project.tasks.getByName(it) }
        val registered = subprojects.flatMap { child ->
            val names = sourceSets(child.name).map { source ->
                "compile" + source.replaceFirstChar { it.uppercase() } + "KotlinMetadata"
            } + listOf("allMetadataJar", "compileKotlinOhosX64", "compileKotlinOhosArm64")
            names.map { child.tasks.getByName(it).path }
        }
        evidence("tasks", mapOf("fingerprint" to fingerprint, "rootTasks" to rootTasks, "registered" to registered))
    }
}

tasks.register("resolveFileKitPort") {
    doLast {
        val configurations = subprojects.flatMap { child ->
            listOf("commonMainResolvableDependenciesMetadata", "ohosX64CompileKlibraries", "ohosArm64CompileKlibraries").map {
                mapOf("module" to child.name, "configuration" to it, "artifacts" to resolved(child.configurations.getByName(it)))
            }
        }
        evidence("resolve", mapOf("fingerprint" to fingerprint, "configurations" to configurations))
    }
}

fun recordStage(name: String, target: String?) {
    tasks.register(name) {
        val producers = subprojects.flatMap { child ->
            if (target == null) sourceSets(child.name).map { source ->
                child.path + ":compile" + source.replaceFirstChar { it.uppercase() } + "KotlinMetadata"
            } + (child.path + ":allMetadataJar") else listOf(child.path + ":compileKotlin" + target)
        }
        dependsOn(producers)
        outputs.upToDateWhen { false }
        doLast {
            val entries = producers.map { path ->
                val producer = project.tasks.getByPath(path)
                val state = producer.state
                check(gradle.taskGraph.hasTask(producer) && state.executed && producer.enabled && !state.noSource && state.failure == null &&
                    state.skipMessage in listOf(null, "UP-TO-DATE", "FROM-CACHE")) { "Invalid FileKit producer: $path" }
                val outputs = records(producer.outputs.files.files.flatMap(::filesOf))
                check(outputs.isNotEmpty()) { "FileKit producer output is missing: $path" }
                val compilerSources = if (target != null) {
                    val compiler = producer as KotlinNativeCompile
                    val expected = expectedFiles.filter {
                        it["module"] == producer.project.name &&
                            it["sourceSet"] != if (target == "OhosX64") "ohosArm64Main" else "ohosX64Main"
                    }.associate { work.resolve(it.getValue("path").toString()).canonicalFile to it.getValue("sha256").toString() }
                    val actual = compiler.sources.files.filter { it.isFile && it.extension == "kt" }.map { it.canonicalFile }.toSet()
                    check(actual == expected.keys && expected.all { (file, hash) -> digest(file) == hash }) {
                        "Actual FileKit compiler sources differ: $path"
                    }
                    check(actual.size == if (producer.project.name == "filekit-core") 22 else 20)
                    records(actual)
                } else emptyList<Map<String, Any>>()
                mapOf("task" to path, "upToDate" to state.upToDate, "skipMessage" to state.skipMessage,
                    "compilerSources" to compilerSources, "inputs" to records(producer.inputs.files.files.flatMap(::filesOf)),
                    "outputs" to outputs, "compilerPlugins" to if (producer is KotlinNativeCompile)
                        records(producer.compilerPluginClasspath?.files.orEmpty()) else emptyList<Map<String, Any>>())
            }
            evidence(name, mapOf("fingerprint" to fingerprint, "stage" to name, "target" to target,
                "sourceManifestSha256" to digest(work.resolve("source-manifest.json")),
                "compiledSourceManifestSha256" to digest(work.resolve("compiled-source-manifest.json")), "producers" to entries))
        }
    }
}
// 使用标准 KGP metadata 任务，不引入替代入口或豁免。
recordStage("recordFileKitMetadata", null)
recordStage("recordFileKitX64", "OhosX64")
recordStage("recordFileKitArm64", "OhosArm64")
tasks.register("publishFileKitPort") {
    dependsOn(subprojects.map { it.path + ":publishAllPublicationsToPortRepository" })
}

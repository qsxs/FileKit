import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

val filekitTargets = extra["filekitTargets"] as List<Map<String, String>>
val filekitCompileTasks = extra["filekitCompileTasks"] as List<String>
val filekitVersion = providers.gradleProperty("filekitVersion").get()
val filekitGroup = "io.github.qsxs.cpf.filekit"

fun fileSha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

fun klibNativeTargets(file: File): String {
    ZipFile(file).use { archive ->
        val manifest = archive.getEntry("default/manifest")
            ?: error("KLIB is missing default/manifest: ${file.name}")
        val contents = archive.getInputStream(manifest).bufferedReader().use { it.readText() }
        return contents.lineSequence()
            .firstOrNull { it.startsWith("native_targets=") }
            ?.substringAfter("=")
            ?: error("KLIB manifest has no native_targets field: ${file.name}")
    }
}

fun readFileKitHash(report: Map<*, *>, rootModule: String, leaf: String): Pair<String, String>? {
    val modules = report["modules"] as? Map<*, *> ?: return null
    val module = modules[rootModule] as? Map<*, *> ?: return null
    val targets = module["targets"] as? Map<*, *> ?: return null
    val target = targets[leaf] as? Map<*, *> ?: return null
    val artifact = target["artifact"] as? String ?: return null
    val hash = target["artifactSha256"] as? String ?: return null
    return artifact to hash
}

tasks.register("verifyFileKitResolution") {
    dependsOn(filekitCompileTasks)

    doLast {
        val expectedReportPath = providers.gradleProperty("filekitMergeReport").orNull
        val expectedReport = expectedReportPath?.let { path ->
            val reportFile = File(path)
            check(reportFile.isFile) { "Merge report does not exist: $path" }
            JsonSlurper().parse(reportFile) as? Map<*, *>
                ?: error("Merge report is not a JSON object: $path")
        }
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()

        for (targetSpec in filekitTargets) {
            val targetName = targetSpec.getValue("target")
            val leaf = targetSpec.getValue("leaf")
            val target = kotlin.targets.getByName(targetName)
            val compilation = target.compilations.getByName("main")
            val configuration = configurations.getByName(compilation.compileDependencyConfigurationName)
            val resolved = configuration.incoming.artifacts.artifacts.filter { artifact ->
                val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                component?.group == filekitGroup &&
                    artifact.file.extension.lowercase() in setOf("aar", "klib") &&
                    (artifact.file.name.contains("filekit-core", ignoreCase = true) ||
                        artifact.file.name.contains("filekit-dialogs", ignoreCase = true))
            }

            for (rootModule in listOf("filekit-core", "filekit-dialogs")) {
                val expected = expectedReport?.let { readFileKitHash(it, rootModule, leaf) }
                val candidates = resolved.filter { artifact ->
                    val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    val ownerModule = component?.module
                    val expectedArtifactName = expected?.first
                    (expectedArtifactName != null && artifact.file.name == expectedArtifactName) ||
                        (expectedArtifactName == null &&
                            (artifact.file.name.contains(rootModule, ignoreCase = true) ||
                                ownerModule == rootModule || ownerModule == "$rootModule-$leaf"))
                }
                check(candidates.size == 1) {
                    "Expected one resolved $rootModule artifact for $targetName, found ${candidates.size} in ${configuration.name}."
                }

                val artifact = candidates.single()
                val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: error("Resolved FileKit artifact has no module coordinate: ${artifact.id.displayName}")
                check(component.group == filekitGroup && component.version == filekitVersion) {
                    "Resolved FileKit coordinate does not match the unified GAV: ${component.displayName}"
                }
                check(component.module == rootModule || component.module == "$rootModule-$leaf") {
                    "Resolved unexpected FileKit module for $targetName: ${component.displayName}"
                }
                val attributes = artifact.variant.attributes.keySet().associate { attribute ->
                    attribute.name to artifact.variant.attributes.getAttribute(attribute).toString()
                }
                check(attributes["org.jetbrains.kotlin.platform.type"] == targetSpec["platform"]) {
                    "Resolved platform variant mismatch for $targetName: $attributes"
                }

                val nativeTarget = if (artifact.file.extension.equals("klib", ignoreCase = true)) {
                    klibNativeTargets(artifact.file)
                } else {
                    ZipFile(artifact.file).use { archive ->
                        check(archive.getEntry("AndroidManifest.xml") != null && archive.getEntry("classes.jar") != null) {
                            "Resolved Android AAR is incomplete: ${artifact.file.name}"
                        }
                    }
                    "android-aar"
                }
                val expectedNativeTarget = targetSpec["nativeTarget"] ?: "android-aar"
                check(nativeTarget == expectedNativeTarget) {
                    "Resolved target mismatch for $targetName: ${artifact.file.name} has $nativeTarget"
                }
                if (targetSpec.containsKey("nativeTarget")) {
                    check(attributes["org.jetbrains.kotlin.native.target"] == expectedNativeTarget) {
                        "Resolved native variant attributes mismatch for $targetName: $attributes"
                    }
                }

                val digest = fileSha256(artifact.file)
                if (expected != null) {
                    check(digest.equals(expected.second, ignoreCase = true)) {
                        "Resolved artifact SHA-256 differs from merge report for ${artifact.file.name}"
                    }
                }
                println(
                    "FILEKIT_RESOLUTION target=$targetName configuration=${configuration.name} " +
                        "coordinate=${component.group}:${component.module}:${component.version} " +
                        "variant=${artifact.variant.displayName} attributes=$attributes file=${artifact.file.name} " +
                        "nativeTarget=$nativeTarget sha256=$digest",
                )
            }
        }
    }
}

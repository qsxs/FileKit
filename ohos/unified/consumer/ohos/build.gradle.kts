import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val filekitVersion = providers.gradleProperty("filekitVersion").get()

kotlin {
    ohosX64()
    ohosArm64()

    sourceSets {
        val commonMain by getting
        val mobileMain = maybeCreate("mobileMain").apply {
            dependsOn(commonMain)
        }
        val ohosMain = maybeCreate("ohosMain").apply {
            dependsOn(mobileMain)
        }
        getByName("ohosX64Main").dependsOn(ohosMain)
        getByName("ohosArm64Main").dependsOn(ohosMain)

        commonMain.dependencies {
            implementation("io.github.qsxs.cpf.filekit:filekit-core:$filekitVersion")
            implementation("io.github.qsxs.cpf.filekit:filekit-dialogs:$filekitVersion")
        }
    }
}

extra["filekitTargets"] = listOf(
    mapOf("target" to "ohosArm64", "leaf" to "ohosarm64", "platform" to "native", "nativeTarget" to "ohos_arm64"),
    mapOf("target" to "ohosX64", "leaf" to "ohosx64", "platform" to "native", "nativeTarget" to "ohos_x64"),
)
val consumerKotlin = extensions.getByType<KotlinMultiplatformExtension>()
extra["filekitCompileTasks"] = listOf("ohosArm64", "ohosX64").map { targetName ->
    consumerKotlin.targets.getByName(targetName).compilations.getByName("main").compileKotlinTaskName
}
apply(from = rootProject.file("../resolution-report.gradle.kts"))

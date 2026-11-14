import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val filekitVersion = providers.gradleProperty("filekitVersion").get()

kotlin {
    androidLibrary {
        namespace = "io.github.qsxs.filekit.consumer.standard"
        compileSdk = 37
        minSdk = 24
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting
        val mobileMain = maybeCreate("mobileMain").apply {
            dependsOn(commonMain)
        }
        getByName("androidMain").dependsOn(mobileMain)
        getByName("iosMain").dependsOn(mobileMain)

        commonMain.dependencies {
            implementation("io.github.qsxs.cpf.filekit:filekit-core:$filekitVersion")
            implementation("io.github.qsxs.cpf.filekit:filekit-dialogs:$filekitVersion")
        }
    }
}

extra["filekitTargets"] = listOf(
    mapOf("target" to "android", "leaf" to "android", "platform" to "androidJvm"),
    mapOf("target" to "iosArm64", "leaf" to "iosarm64", "platform" to "native", "nativeTarget" to "ios_arm64"),
    mapOf("target" to "iosSimulatorArm64", "leaf" to "iossimulatorarm64", "platform" to "native", "nativeTarget" to "ios_simulator_arm64"),
)
val consumerKotlin = extensions.getByType<KotlinMultiplatformExtension>()
extra["filekitCompileTasks"] = listOf("android", "iosArm64", "iosSimulatorArm64").map { targetName ->
    consumerKotlin.targets.getByName(targetName).compilations.getByName("main").compileKotlinTaskName
}
apply(from = rootProject.file("../resolution-report.gradle.kts"))

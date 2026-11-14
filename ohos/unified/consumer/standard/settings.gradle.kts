pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").orElse("2.4.20").get()
    val agpVersion = providers.gradleProperty("agpVersion").orElse("9.4.0").get()
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("com.android.kotlin.multiplatform.library") version agpVersion
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "FileKitPublication"
                    url = uri(providers.gradleProperty("filekitRepository").get())
                }
            }
            filter {
                includeGroup("io.github.qsxs.cpf.filekit")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "filekit-unified-standard-consumer"

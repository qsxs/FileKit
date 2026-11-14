pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").orElse("2.4.21-0.1.0-13").get()
    val cpfPluginRepository = providers.gradleProperty("cpfPluginRepository").get()
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
    }
    repositories {
        maven(url = cpfPluginRepository)
        gradlePluginPortal()
        google()
        mavenCentral()
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
        maven(url = providers.gradleProperty("cpfPluginRepository").get())
        maven(url = providers.gradleProperty("cpfDependencyRepository").get())
        mavenCentral()
        google()
    }
}

rootProject.name = "filekit-unified-ohos-consumer"

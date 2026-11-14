pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        kotlin("multiplatform") version "2.4.21-0.1.0-13"
        kotlin("plugin.serialization") version "2.4.21-0.1.0-13"
        kotlin("plugin.compose") version "2.4.21-0.1.0-13"
        id("org.jetbrains.compose") version "1.12.0-0.1.0-23"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        mavenCentral()
        google()
    }
}

rootProject.name = "filekit-ohos-unified"
include(":filekit-core", ":filekit-dialogs")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
    }
}
plugins { id("dev.kikugie.stonecutter") version "0.9.8" }
stonecutter {
    create(rootProject) {
        versions("26.2", "26.3")
        vcsVersion = "26.3"
    }
}
rootProject.name = "Seedy"

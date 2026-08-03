pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    includeBuild("..")
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
includeBuild("..")
rootProject.name = "hotreload-sample"
include(":app", ":feature")

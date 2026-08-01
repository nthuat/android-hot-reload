pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // gradle-tooling-api is not published to Maven Central for modern versions;
        // it is only available from Gradle's own repo. Required by cli/build.gradle.kts.
        maven("https://repo.gradle.org/gradle/libs-releases")
    }
}
rootProject.name = "android-hot-reload"
include(":cli", ":gradle-plugin", ":runtime", ":agent")

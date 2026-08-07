plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.vanniktech.maven.publish)
    // Publishes to the Gradle Plugin Portal (./gradlew :gradle-plugin:publishPlugins), so
    // consumers can resolve the plugin by id with no repository setup at all -- the Portal is in
    // Gradle's default pluginManagement repositories, Maven Central is not. Central publishing is
    // unaffected and still happens through mavenPublishing below; the two coexist deliberately,
    // since :runtime is a plain library that only Central can serve.
    alias(libs.plugins.gradle.plugin.publish)
}
group = "dev.thuat"
version = "0.1.8"
gradlePlugin {
    // Required by the Portal (it rejects a submission without them) and unused by Central.
    website.set("https://github.com/nthuat/android-hot-reload")
    vcsUrl.set("https://github.com/nthuat/android-hot-reload.git")
    plugins {
        create("hotreload") {
            id = "dev.thuat.hotreload"
            implementationClass = "dev.thuat.hotreload.gradle.HotReloadPlugin"
            displayName = "Android Hot Reload"
            description = "Hot reload for Jetpack Compose on real Android devices: edit a " +
                "composable, save, and the running app updates in place with `remember` state " +
                "preserved. No reinstall, no activity restart, works from any editor."
            tags.set(listOf("android", "compose", "hot-reload", "live-edit", "jetpack-compose"))
        }
    }
}
dependencies {
    // Type access only (ComposeCompilerGradlePluginExtension); must not leak into the plugin's
    // runtime deps so consumers don't get a transitive compose-compiler-gradle-plugin.
    compileOnly(libs.compose.compiler.gradle.plugin)
    testImplementation(libs.junit4)
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    // Test-only: lets unit tests register a fake "org.jetbrains.kotlin.plugin.compose" plugin
    // that creates a real ComposeCompilerGradlePluginExtension, so the key-meta flag assertion
    // exercises the real extension type without needing a full AGP+Kotlin TestKit build.
    testImplementation(libs.compose.compiler.gradle.plugin)
}
kotlin { jvmToolchain(17) }

// java-gradle-plugin's automatically generated "pluginMaven" publication (plus the id-based
// marker publication for "dev.thuat.hotreload") gets sources/javadoc jars from
// com.vanniktech.maven.publish's auto-detected GradlePlugin platform.
mavenPublishing {
    publishToMavenCentral()

    coordinates(group.toString(), "gradle-plugin", version.toString())
    pom {
        name.set("Android Hot Reload Gradle Plugin")
        description.set(
            "Gradle plugin (id \"dev.thuat.hotreload\") that wires the android-hot-reload " +
                "runtime dependency and Compose compiler function-key metadata flag into a " +
                "project's debug build.",
        )
        // Shared metadata identical to :runtime's pom {} (see the comment there for why this
        // isn't factored out into a shared script instead).
        url.set("https://github.com/nthuat/android-hot-reload")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("nthuat")
                name.set("Thuat Nguyen")
                email.set("thuat26.ng@gmail.com")
            }
        }
        scm {
            url.set("https://github.com/nthuat/android-hot-reload")
            connection.set("scm:git:git://github.com/nthuat/android-hot-reload.git")
            developerConnection.set("scm:git:ssh://git@github.com/nthuat/android-hot-reload.git")
        }
    }

    // GPG signing is mandatory for Central, but must not block contributors who lack a key — see
    // the matching comment in runtime/build.gradle.kts.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}
group = "dev.thuat"
version = "0.1.6"
android {
    namespace = "dev.thuat.hotreload.runtime"
    compileSdk = 35
    // 21, not 26: this library only touches APIs from the platform's earliest days
    // (Activity/Application/ContentProvider/Handler/Looper/Log). Hot reload itself needs API 26+
    // because that's the JVMTI attach floor, but the *library* is inert until the agent attaches,
    // so declaring 26 here only served to break the manifest merger for any consumer whose app
    // has a lower minSdk — Google's own JetNews sample (minSdk 23) can't even build with it.
    defaultConfig {
        minSdk = 21
        // Bakes this library's own published `version` (below) into a generated BuildConfig
        // constant instead of a hand-maintained literal, so it can never drift from what the
        // artifact actually is — see ComposeInvalidator.runtimeVersion, which the JVMTI agent
        // reads via JNI to report this in the PING reply (agent.cpp's ReadRuntimeVersion /
        // Protocol.pingRuntimeVersionOf). This is the mechanism the CLI compares its own version
        // against before trusting a reload (see ReloadOrchestrator.checkRuntimeVersion) — the
        // actual fix for "newer CLI + older runtime silently no-ops" (see the fix report).
        buildConfigField("String", "HOTRELOAD_RUNTIME_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Publishing (single "release" variant, unqualified by build type so it resolves for both
    // debugImplementation and releaseImplementation consumers) plus the release/sources/javadoc
    // jar publication is handled by com.vanniktech.maven.publish, which auto-detects
    // com.android.library and configures an AndroidSingleVariantLibrary("release") publication.
}
dependencies {
    // compileOnly: the app supplies its own Compose runtime; we only reflect into it
    compileOnly(platform(libs.compose.bom))
    compileOnly("androidx.compose.runtime:runtime")

    // Plain-JVM unit tests only (see ComposeInvalidatorTest): invalidateAll has no Android
    // imports, so no Robolectric/instrumented test runner is needed for it.
    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}

mavenPublishing {
    publishToMavenCentral()

    // artifactId is "hotreload-runtime" (not the directory name "runtime") — set in
    // settings.gradle.kts as the single source of truth for the published coordinate.
    coordinates(group.toString(), "hotreload-runtime", version.toString())
    pom {
        name.set("Android Hot Reload Runtime")
        description.set(
            "In-app runtime (a ContentProvider + reflection hook into Jetpack Compose's " +
                "HotReloader) that the android-hot-reload Gradle plugin injects into debug " +
                "builds so JVMTI-redefined classes trigger a recomposition.",
        )
        // Shared metadata identical to :gradle-plugin's pom {} — Central rejects incomplete
        // POMs, every field below is mandatory. Duplicated rather than factored into a shared
        // `apply(from = ...)` script: Kotlin DSL scripts loaded that way don't see plugin classes
        // brought in via the enclosing script's `plugins {}` block, so `configure<MavenPublishBaseExtension>`
        // can't resolve there — see gradle-plugin/build.gradle.kts for the twin of this block.
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

    // GPG signing is mandatory for Central, but must not block contributors who lack a key:
    // signAllPublications() marks signing as REQUIRED for every non-SNAPSHOT version (fails the
    // build if no signatory is configured), so it must only be called when key material is
    // actually present. ORG_GRADLE_PROJECT_signingInMemoryKey (or its gradle.properties
    // equivalent, signingInMemoryKey) is only ever set in CI/release environments, so a bare
    // `./gradlew publishToMavenLocal` checkout stays signature-free and keeps working.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}

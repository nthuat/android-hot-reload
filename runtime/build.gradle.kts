plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); `maven-publish` }
group = "dev.thuat"
version = "0.1.0-SNAPSHOT"
android {
    namespace = "dev.thuat.hotreload.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Publish one variant, unqualified by build type, so it resolves for both
    // debugImplementation and releaseImplementation consumers (AGP strips the build-type
    // attribute from single-variant publications).
    publishing { singleVariant("release") }
}
dependencies {
    // compileOnly: the app supplies its own Compose runtime; we only reflect into it
    compileOnly(platform(libs.compose.bom))
    compileOnly("androidx.compose.runtime:runtime")
}
// AGP creates the "release" component during afterEvaluate; the publication must be
// registered after that, not eagerly at script evaluation time.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                // artifactId defaults to project.name, renamed to "hotreload-runtime" in
                // settings.gradle.kts — single source of truth for the published coordinate.
                from(components["release"])
            }
        }
    }
}

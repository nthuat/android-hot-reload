plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Declared here (not only in :gradle-plugin, which is the sole project that applies it) so it
    // lands on the ROOT buildscript classpath and every subproject resolves its publishing plugins
    // through one classloader. Applying com.gradle.plugin-publish in :gradle-plugin alone gives
    // that project a different buildscript classpath from :hotreload-runtime; vanniktech's plugin
    // is then loaded twice, its cross-project MavenCentralBuildService gets two distinct type
    // identities, and every Central publish fails at task-graph time with:
    //   Cannot set the value of task ':hotreload-runtime:prepareMavenCentralPublishing' property
    //   'buildService' of type MavenCentralBuildService using a provider of type
    //   MavenCentralBuildService
    //
    // Hoisting ONLY this one is deliberate: also declaring vanniktech here fixes the publish but
    // breaks `sample/`, which includes this build as a composite. MavenCentralBuildService then
    // lands on the included build's root-project classloader scope, and the sample's configuration
    // cache entry cannot deserialize it:
    //   Class 'com.vanniktech.maven.publish.central.MavenCentralBuildService' not found in class
    //   loader '...root-project[:](export)'
    // which took e2e down (it wants exit 2 from the incompatible-change step and got 1). Unlike
    // the Kotlin `__buildFusService__` failure, that shape is not configuration-cache-shaped to
    // isConfigurationCacheFailure, so the CLI cannot fall back either.
    //
    // Verified by bisection against BOTH `publishToMavenCentral --dry-run` and a
    // store-then-reuse configuration-cache run of `sample/`. Changing this line needs both.
    alias(libs.plugins.gradle.plugin.publish) apply false
}

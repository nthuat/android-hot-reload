plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    // Both publishing plugins are declared here, not only in the subprojects that apply them, so
    // they land on the ROOT buildscript classpath and every subproject resolves them through one
    // classloader. Without this, adding com.gradle.plugin-publish to :gradle-plugin alone gives
    // that project a different buildscript classpath from :hotreload-runtime, vanniktech's plugin
    // is then loaded twice, and its cross-project MavenCentralBuildService has two distinct type
    // identities -- which fails the whole Central publish at task-graph time with:
    //   Cannot set the value of task ':hotreload-runtime:prepareMavenCentralPublishing' property
    //   'buildService' of type MavenCentralBuildService using a provider of type
    //   MavenCentralBuildService
    // Reproduced by bisecting the plugin-publish line in and out against
    // `publishToMavenCentral --dry-run`. Keep both entries even if one looks redundant.
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.gradle.plugin.publish) apply false
}

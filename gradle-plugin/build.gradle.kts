plugins { alias(libs.plugins.kotlin.jvm); `java-gradle-plugin`; `maven-publish` }
group = "dev.thuat"
version = "0.1.0-SNAPSHOT"
gradlePlugin {
    plugins {
        create("hotreload") {
            id = "dev.thuat.hotreload"
            implementationClass = "dev.thuat.hotreload.gradle.HotReloadPlugin"
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
}
kotlin { jvmToolchain(17) }

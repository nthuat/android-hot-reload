plugins { alias(libs.plugins.kotlin.jvm); `java-gradle-plugin` }
group = "dev.hotreload"
version = "0.1.0-SNAPSHOT"
gradlePlugin {
    plugins {
        create("hotreload") {
            id = "dev.hotreload"
            implementationClass = "dev.hotreload.gradle.HotReloadPlugin"
        }
    }
}
dependencies {
    testImplementation(libs.junit4)
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(17) }

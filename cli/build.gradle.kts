plugins { alias(libs.plugins.kotlin.jvm); application }
application { mainClass.set("dev.hotreload.cli.MainKt") }
dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.r8)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16") // tooling API logs
    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(17) }

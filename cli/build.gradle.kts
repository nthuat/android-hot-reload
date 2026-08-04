import org.gradle.jvm.application.tasks.CreateStartScripts

plugins { alias(libs.plugins.kotlin.jvm); application }

application {
    mainClass.set("dev.thuat.hotreload.cli.MainKt")
}

dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.r8)
    implementation(libs.asm.tree)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16") // tooling API logs
    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

// Lets Main.kt find the bundled agent .so files at "$APP_HOME/agent/<abi>/..." with zero
// configuration, as long as the CLI was launched via its own generated start script (bin/cli).
// Plain env var export, not a "-Dhotreload.home=..." JVM system property: Gradle's Unix
// start-script template runs DEFAULT_JVM_OPTS/JAVA_OPTS through an xargs+sed+eval pipeline that
// backslash-escapes every '$' (anti shell-injection hardening), so a JVM arg referencing
// "$APP_HOME" comes out the other end as the four literal characters "$APP_HOME" instead of an
// expanded path — confirmed by running the installed CLI. Exporting a plain env var ahead of
// that pipeline sidesteps it entirely. Absent when run another way (e.g. `gradle run` from a dev
// checkout) — Main.kt falls back to a CWD-relative default in that case.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val unixScriptFile = file(unixScript)
        val windowsScriptFile = file(windowsScript)
        unixScriptFile.writeText(
            unixScriptFile.readText().replaceFirst(
                "DEFAULT_JVM_OPTS=", "export HOTRELOAD_HOME=\"\$APP_HOME\"\nDEFAULT_JVM_OPTS="
            )
        )
        windowsScriptFile.writeText(
            windowsScriptFile.readText().replaceFirst(
                "set DEFAULT_JVM_OPTS=", "set HOTRELOAD_HOME=%APP_HOME%\nset DEFAULT_JVM_OPTS="
            )
        )
    }
}

// Ship the agent's built .so files inside the CLI's own distribution, laid out by ABI
// (<installDir>/agent/<abi>/libhotreload_agent.so), so a fresh consumer never has to locate or
// configure an --agent-so-dir manually — see Main.kt's resolveAgentSoDir.
distributions {
    main {
        contents {
            from(project(":agent").layout.buildDirectory.dir(
                "intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"
            )) {
                into("agent")
            }
        }
    }
}

// The merged-native-libs dir above is a plain path reference, not a task-output provider, so
// Gradle won't infer the dependency on its own — installDist/distZip/distTar would otherwise
// copy whatever (possibly stale or absent) contents happen to be on disk.
listOf("installDist", "distZip", "distTar").forEach { taskName ->
    tasks.named(taskName) { dependsOn(":agent:assembleDebug") }
}

package dev.hotreload.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            project.dependencies.add("debugImplementation", "dev.hotreload:runtime:0.1.0-SNAPSHOT")
        }
    }
}

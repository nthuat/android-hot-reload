package dev.thuat.hotreload.gradle

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Downloads the `cli.zip` release asset matching this Gradle plugin's own resolved version and
 * unpacks it into the project's build dir, so a consumer never has to manually find the right
 * release on GitHub, download it, unzip it, and remember where it went — and the CLI version can
 * never drift from the applied plugin version, since it's the same [InstallCliTask.version] value
 * [HotReloadPlugin] derives for itself (see [CliInstallSupport]).
 *
 * Deliberately download-only — do NOT wire this into running a reload. `ReloadOrchestrator`
 * spawns a Gradle build via the Tooling API to compile the edited file; invoking that from
 * *inside* a Gradle task would try to acquire the same build lock this task's own Gradle daemon
 * is already holding and deadlock. Downloading a zip has no such problem; running the CLI does.
 */
abstract class InstallCliTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    /** `build/hotreload/cli` — the unpacked distribution ends up here, `bin/cli` included. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    // Neither of these affects what gets downloaded/unpacked, only the printed usage line, so
    // they're not @Input — a rename of the app module (say) shouldn't invalidate up-to-date-ness.
    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:Internal
    abstract val applicationId: Property<String>

    init {
        group = "hot reload"
        description = "Downloads the cli.zip release matching this plugin's version into build/hotreload/cli."
        applicationId.convention("")
    }

    @TaskAction
    fun install() {
        val resolvedVersion = version.get()
        val targetDir = outputDir.get().asFile
        val binCli = File(targetDir, "bin/cli")
        val versionMarker = File(targetDir, ".hotreload-cli-version")

        // Gradle's own up-to-date checking (declared @Input/@OutputDirectory above) already skips
        // re-running this task at all when nothing changed. This second, in-action check covers
        // the cases that bypass that — `--rerun-tasks`, a deleted `.gradle` state dir with the
        // output left behind — so a forced re-execution still doesn't re-download when the
        // already-unpacked CLI already matches.
        if (binCli.isFile && versionMarker.isFile && versionMarker.readText() == resolvedVersion) {
            logger.lifecycle("hotReloadInstallCli: cli $resolvedVersion already installed at $targetDir, skipping download.")
        } else {
            download(resolvedVersion, targetDir)
            versionMarker.writeText(resolvedVersion)
            if (!binCli.setExecutable(true, false)) {
                logger.warn(
                    "hotReloadInstallCli: could not set the executable bit on $binCli; " +
                        "run 'chmod +x $binCli' manually if 'cli run' fails with permission denied.",
                )
            }
        }
        val wrapper = writeWrapper(resolvedVersion)
        printUsage(wrapper)
    }

    /** See [HotReloadWrapperScript] for the generated content and the clobber guard. */
    private fun writeWrapper(resolvedVersion: String): File {
        val pkg = applicationId.orNull?.takeIf { it.isNotBlank() } ?: ""
        return HotReloadWrapperScript.writeTo(projectDir.get().asFile, pkg, resolvedVersion)
    }

    private fun download(resolvedVersion: String, targetDir: File) {
        val url = CliInstallSupport.downloadUrl(resolvedVersion)
        logger.lifecycle("hotReloadInstallCli: downloading $url")
        val buildDir = targetDir.parentFile
        buildDir.mkdirs()
        val zipFile = File(buildDir, "cli-download.zip")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = runCatching {
            client.send(request, HttpResponse.BodyHandlers.ofFile(zipFile.toPath()))
        }.getOrElse { e ->
            throw GradleException("hotReloadInstallCli: failed to download $url: ${e.message}", e)
        }
        if (response.statusCode() != 200) {
            zipFile.delete()
            throw GradleException(
                "hotReloadInstallCli: failed to download $url (HTTP ${response.statusCode()}). " +
                    "Check that release ${CliInstallSupport.releaseTag(resolvedVersion)} exists on " +
                    "https://github.com/nthuat/android-hot-reload/releases. If this project uses a " +
                    "composite build (includeBuild) with no published release for this version yet, " +
                    "set hotreload.runtimeCoordinate or apply the plugin with an explicit released version.",
            )
        }

        targetDir.deleteRecursively()
        unzip(zipFile, buildDir)
        zipFile.delete()
    }

    // The release zip's own top-level entry is "cli/" (the :cli module's application-plugin
    // distribution name) — unzipping straight into outputDir's *parent* lands that entry exactly
    // at outputDir, no extra nesting or stripping needed.
    private fun unzip(zipFile: File, destinationParent: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destinationParent, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun printUsage(wrapper: File) {
        logger.lifecycle(
            "hotReloadInstallCli: ready. Run ./${wrapper.name} run (bootstrap / cycle --file ... also work).\n" +
                "  It has machine-specific absolute paths baked in -- add '${wrapper.name}' to .gitignore.",
        )
    }
}

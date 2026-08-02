package dev.hotreload.cli

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// Dexing a class in isolation (fresh D8 invocation on just its .class file) cannot be used
// for RedefineClasses: D8's synthetic-lambda naming (e.g. the bridge method backing every
// Compose composable's restart lambda, `$r8$lambda$<hash>`) depends on the toolchain/build
// context, not just the class bytes, so an isolated re-dex mints a *different* hash than
// the one already loaded on the device — ART then reports the old-hash method as deleted
// and the new-hash one as added (JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED).
// Verified empirically: rebuilding via AGP's own dexBuilder/mergeDex tasks reproduces the
// exact same hash across edits (it's stable per call site, not per edit), so instead of
// re-dexing, this locates the class inside AGP's already-merged, already-consistent dex
// output (GradleCompiler must have run the merge-dex tasks first) and splits just that one
// class back out with `--file-per-class` — a dex-to-dex repackage, so no new hashes are
// minted, only the original bytes are extracted.
class DexPackager(
    private val projectDir: Path,
    private val appModule: String = ":app",
    private val minApi: Int = 26,
) {
    fun dexClass(changed: ChangedClass, outDir: Path): Path {
        val simpleName = changed.binaryName.substringAfterLast('.')
        val work = Files.createTempDirectory("hotreload-d8-split")
        try {
            for (mergedDex in mergedDexCandidates()) {
                val splitDir = Files.createTempDirectory(work, "split")
                D8.run(
                    D8Command.builder()
                        .addProgramFiles(mergedDex)
                        .setIntermediate(true)
                        .setMinApiLevel(minApi)
                        .setOutput(splitDir, OutputMode.DexFilePerClass)
                        .build()
                )
                val hit = Files.walk(splitDir).use { s ->
                    s.filter { it.fileName.toString() == "$simpleName.dex" }.findFirst()
                }
                if (hit.isPresent) {
                    Files.createDirectories(outDir)
                    val target = outDir.resolve("$simpleName.dex")
                    Files.move(hit.get(), target, StandardCopyOption.REPLACE_EXISTING)
                    return target
                }
            }
            error(
                "class ${changed.binaryName} not found in $appModule's merged dex output " +
                    "(mergeProjectDexDebug/mergeLibDexDebug) — run a full rebuild + bootstrap"
            )
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    // v1 assumes a conventional single top-level application module (default ":app").
    // Dexing always culminates there: a changed class lives either directly in the app
    // module (mergeProjectDexDebug) or in a library module the app depends on
    // (mergeLibDexDebug). Both are sized to project code (tens of classes), unlike
    // mergeExtDexDebug — every external dependency merged together — which a source edit
    // never touches, so it's deliberately excluded to keep the per-cycle split fast.
    private fun mergedDexCandidates(): List<Path> {
        val dexDebugDir = projectDir
            .resolve(appModule.removePrefix(":").replace(':', File.separatorChar))
            .resolve("build/intermediates/dex/debug")
        return listOf("mergeProjectDexDebug", "mergeLibDexDebug").flatMap { task ->
            val taskDir = dexDebugDir.resolve(task)
            if (!Files.isDirectory(taskDir)) return@flatMap emptyList<Path>()
            Files.list(taskDir).use { s ->
                s.filter { Files.isDirectory(it) }.map { it.resolve("classes.dex") }.toList()
            }.filter { Files.exists(it) }
        }
    }
}

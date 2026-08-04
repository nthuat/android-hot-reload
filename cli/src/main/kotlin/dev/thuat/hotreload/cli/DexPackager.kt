package dev.thuat.hotreload.cli

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

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
    // Batch entry point: splits each merged-dex bucket AT MOST ONCE per call and serves every
    // wanted class from that single pass, instead of the old dexClass()'s per-class loop that
    // re-split every bucket from scratch for each changed class (F1: a 3-class edit did on the
    // order of 20 redundant full-bucket D8 splits, ~17s of pure waste on a real project).
    //
    // Buckets are split across a bounded thread pool: each split is CPU-bound, independent (own
    // D8Command, own output dir), and D8.run has no shared mutable state across invocations
    // (verified via javap — the only static state in D8.class is a static final boolean; the
    // executor D8 uses internally is created and torn down within a single run() call) — so
    // concurrent D8.run calls with distinct output dirs are safe.
    //
    // Deliberately parallel-all-buckets rather than early-exit-on-first-satisfying-bucket: the
    // two don't compose (stopping early only helps a *sequential* scan), and with ~7 buckets on
    // a multicore machine, splitting all of them concurrently is itself only ~1s wall time —
    // faster than a sequential early exit would usually manage anyway. Returns a map from each
    // requested class to its extracted dex path; a class present in no bucket is reported via a
    // single error() listing every missing class (caller decides what to do — today that means
    // the whole cycle fails, matching dexClass's old single-class behavior).
    fun dexClasses(changed: List<ChangedClass>, outDir: Path): Map<ChangedClass, Path> {
        if (changed.isEmpty()) return emptyMap()

        // D8's file-per-class output mirrors the class's package as directories, e.g.
        // com.b.Util -> com/b/Util.dex — package-qualified, not just the bare simple name.
        // Matching on bare "<SimpleName>.dex" (old behavior) collided whenever two classes in
        // different packages shared a simple name (com.a.Util vs com.b.Util): whichever the
        // walk happened to visit first won, so the wrong module's bytes could be pushed for a
        // same-named class and a valid edit misreported as an incompatible change.
        val expectedRelByClass = changed.associateWith { it.binaryName.replace('.', '/') + ".dex" }
        val buckets = mergedDexCandidates()
        val work = Files.createTempDirectory("hotreload-d8-split")
        try {
            val poolSize = buckets.size.coerceAtMost(Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(poolSize)
            val splitDirs: List<Path> = try {
                val futures = buckets.map { bucket ->
                    pool.submit(
                        Callable {
                            val splitDir = Files.createTempDirectory(work, "split")
                            D8.run(
                                D8Command.builder()
                                    .addProgramFiles(bucket)
                                    .setIntermediate(true)
                                    .setMinApiLevel(minApi)
                                    .setOutput(splitDir, OutputMode.DexFilePerClass)
                                    .build()
                            )
                            splitDir
                        }
                    )
                }
                futures.map { future ->
                    try {
                        future.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            } finally {
                pool.shutdown()
            }

            Files.createDirectories(outDir)
            val results = mutableMapOf<ChangedClass, Path>()
            for (splitDir in splitDirs) {
                for ((cc, expectedRel) in expectedRelByClass) {
                    if (cc in results) continue
                    val hit = splitDir.resolve(expectedRel)
                    if (Files.exists(hit)) {
                        // Collision-free output filename: fully-qualified name (dots ->
                        // underscores) instead of the bare simple name two different packages
                        // could share.
                        val target = outDir.resolve("${cc.binaryName.replace('.', '_')}.dex")
                        Files.move(hit, target, StandardCopyOption.REPLACE_EXISTING)
                        results[cc] = target
                    }
                }
            }

            val missing = changed.filterNot { it in results }
            if (missing.isNotEmpty()) {
                error(
                    "class(es) ${missing.joinToString { it.binaryName }} not found in $appModule's merged " +
                        "dex output (mergeProjectDexDebug/mergeLibDexDebug), run a full rebuild + bootstrap"
                )
            }
            return results
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

package dev.hotreload.cli

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DexPackager(private val minApi: Int = 26) {
    fun dexClass(changed: ChangedClass, outDir: Path): Path {
        val work = Files.createTempDirectory("hotreload-d8")
        try {
            D8.run(
                D8Command.builder()
                    .addProgramFiles(changed.classFile)
                    .setMinApiLevel(minApi)
                    .setOutput(work, OutputMode.DexIndexed)
                    .build()
            )
            Files.createDirectories(outDir)
            val simpleName = changed.binaryName.substringAfterLast('.')
            val target = outDir.resolve("$simpleName.dex")
            Files.move(work.resolve("classes.dex"), target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}

// Known ceiling: D8 desugaring of a NEW lambda in an edited body emits an extra synthetic class in the dex; ART `RedefineClasses` will reject the unknown class and the CLI routes to the rebuild path (spec risk item). No handling here.

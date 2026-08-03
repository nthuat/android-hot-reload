package dev.thuat.hotreload.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.internal.FunctionKeyMeta
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ComposeInvalidator {
    private const val TAG = "HotReload"
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val REPLY_TIMEOUT_SECONDS = 2L

    /**
     * No-op; referencing this from [HotReloadInitProvider] on app startup forces this
     * class to load. Android classes load lazily on first use and nothing else touches
     * this object before a reload happens — without an eager load here, the JVMTI
     * agent's `GetLoadedClasses` lookup in `NotifyRuntime` finds nothing and the
     * recompose signal silently no-ops (logged agent-side as "ComposeInvalidator not
     * loaded; skipping recompose signal").
     */
    @JvmStatic
    fun ensureLoaded() {}

    /**
     * Called by the JVMTI agent via JNI after RedefineClasses succeeds, once per
     * redefined class, with its binary name (e.g. "dev.thuat.hotreload.sample.feature.GreetingKt").
     * Returns which tier actually fired ("tier1"/"tier2"/"tier3"), or "tier-timeout" if the
     * main-thread work didn't finish within [REPLY_TIMEOUT_SECONDS] — so the CLI can surface
     * the real state guarantee to the user instead of just "reloaded".
     *
     * The invalidation work itself must run on the main thread (Compose's runtime hooks and
     * `Activity.recreate()` both require it), but this method is called from the agent's own
     * attached JNI thread (the socket server thread), not the main thread — so blocking here
     * on a latch signaled by the posted main-thread work cannot deadlock; they're always two
     * different threads.
     *
     * Three-tier fallback chain, tier taken always logged at [TAG]:
     *  1. Group-key invalidation (Live Edit's mechanism) — re-executes only the affected
     *     recompose scopes; preserves `remember` state.
     *  2. Whole-composition rebuild via `HotReloader` reflection — loses `remember` state.
     *  3. `Activity.recreate()` — last resort when Compose's runtime hooks are unreachable.
     */
    @JvmStatic
    fun reload(binaryNames: Array<String>): String {
        val latch = CountDownLatch(1)
        var tier = "tier-timeout"
        mainHandler.post {
            try {
                val keys = binaryNames.flatMap(::keysForClass)
                tier = when {
                    keys.isNotEmpty() && invalidateGroupsWithKeys(keys) -> {
                        Log.i(TAG, "tier1: group-key invalidation, keys=$keys")
                        "tier1"
                    }
                    invalidateViaHotReloader() -> {
                        Log.i(TAG, "tier2: whole-composition rebuild via HotReloader")
                        "tier2"
                    }
                    else -> {
                        recreateForegroundActivity()
                        "tier3"
                    }
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return tier
    }

    // Compose compiler option `generateFunctionKeyMetaClasses=true` (enabled by the gradle
    // plugin on debug builds) emits a sibling `<FileFacade>$KeyMeta` class per *source file*,
    // carrying one repeatable @FunctionKeyMeta(key, startOffset, endOffset) per composable
    // function or nested composable lambda declared anywhere in that file — including
    // composables that are members of a class, not just top-level ones.
    //
    // The redefined binary name doesn't always tell you the file facade directly:
    //  (a) A top-level composable's own class or a nested composable lambda (e.g.
    //      `GreetingKt`, `GreetingKt$Greeting$1$2`) — the segment before the first '$' IS
    //      already the file facade, so `<outer>$KeyMeta` is correct.
    //  (b) A composable that's a MEMBER of a class declared in that file (e.g. `class
    //      MyScreen { @Composable fun Body() }` in MyScreen.kt) redefines `MyScreen` itself,
    //      not a `MyScreenKt` class — but the KeyMeta sibling is still attached to the file
    //      facade, `MyScreenKt$KeyMeta`, which is a *different* class from `MyScreen$KeyMeta`
    //      (nonexistent). Candidate (a) alone silently finds zero keys here, falls through to
    //      tier-2, and wipes all `remember` state on every reload of a member composable.
    // Try both; union whichever load (Class.forName failures for a missing candidate are
    // expected and silent — only a total miss across every candidate logs a warning). A single
    // edit commonly redefines several classes from the same file across one reload() call (the
    // batch now carries all of them — see agent.cpp/Protocol.RECORD_SEP), so keys from multiple
    // classes are already unioned by the caller's `binaryNames.flatMap(::keysForClass)`;
    // redundantly re-finding the same keys from multiple candidates/classes is harmless
    // (invalidateGroupsWithKey is idempotent).
    private fun keysForClass(binaryName: String): List<Int> {
        val outer = binaryName.substringBefore('$')
        val candidates = linkedSetOf("$outer\$KeyMeta", "${outer}Kt\$KeyMeta")
        val keys = candidates.flatMap { keyMetaName -> loadKeyMetaKeys(binaryName, keyMetaName) }.distinct()
        if (keys.isEmpty()) {
            Log.w(TAG, "keysForClass($binaryName): no KeyMeta class found among candidates $candidates")
        }
        return keys
    }

    private fun loadKeyMetaKeys(binaryName: String, keyMetaName: String): List<Int> = try {
        // Load via `binaryName`'s own classloader: `binaryName` is guaranteed loaded (the agent
        // just redefined it), whereas `outer` derived from it is not always the same class and
        // isn't guaranteed loaded yet on its own.
        val target = Class.forName(binaryName)
        val keyMeta = Class.forName(keyMetaName, false, target.classLoader)
        keyMeta.getAnnotationsByType(FunctionKeyMeta::class.java).map { it.key }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun invalidateGroupsWithKeys(keys: List<Int>): Boolean {
        val invalidate = resolveInvalidateGroupsWithKey() ?: return false
        var any = false
        for (key in keys) {
            try {
                invalidate(key)
                any = true
            } catch (t: Throwable) {
                Log.w(TAG, "invalidateGroupsWithKey($key) failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return any
    }

    // Same hook Android Studio Live Edit uses. Probe the public wrapper first, then the
    // internal Companion homes directly in case a future Compose version drops the wrapper.
    private fun resolveInvalidateGroupsWithKey(): ((Int) -> Unit)? {
        try {
            val cls = Class.forName("androidx.compose.runtime.HotReloaderKt")
            val method = cls.getDeclaredMethod("invalidateGroupsWithKey", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            return { key -> method.invoke(null, key) }
        } catch (t: Throwable) {
            Log.w(TAG, "HotReloaderKt.invalidateGroupsWithKey unreachable: ${t.javaClass.simpleName}: ${t.message}")
        }
        for (owner in listOf("androidx.compose.runtime.HotReloader", "androidx.compose.runtime.Recomposer")) {
            try {
                val companion = Class.forName(owner).getDeclaredField("Companion")
                    .apply { isAccessible = true }.get(null)
                val method = companion.javaClass.declaredMethods
                    .single { it.name.startsWith("invalidateGroupsWithKey") }
                    .apply { isAccessible = true }
                return { key -> method.invoke(companion, key) }
            } catch (t: Throwable) {
                Log.w(TAG, "$owner\$Companion.invalidateGroupsWithKey unreachable: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return null
    }

    // Compose runtime internal API, same hook JetBrains desktop hot reload uses.
    // Shape (Compose 1.7.x): class androidx.compose.runtime.HotReloader with Companion
    // methods saveStateAndDispose(Any): Any and loadStateAndCompose(Any): Unit.
    private fun invalidateViaHotReloader(): Boolean = try {
        val cls = Class.forName("androidx.compose.runtime.HotReloader")
        val companion = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val save = companion.javaClass.declaredMethods.single { it.name == "saveStateAndDispose" }
            .apply { isAccessible = true }
        val load = companion.javaClass.declaredMethods.single { it.name == "loadStateAndCompose" }
            .apply { isAccessible = true }
        val token = save.invoke(companion, Any())
        load.invoke(companion, token)
        Log.i(TAG, "Recomposed via HotReloader")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "HotReloader reflection failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun recreateForegroundActivity() {
        val activity = ActivityTracker.foreground
        if (activity != null) {
            Log.w(TAG, "tier3: recreating ${activity.javaClass.simpleName}")
            activity.recreate()
        } else {
            Log.e(TAG, "Reload signalled but no foreground activity to refresh")
        }
    }
}

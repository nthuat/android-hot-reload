package dev.thuat.hotreload.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.internal.FunctionKeyMeta
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Runs `invalidate` for every key, routing any thrown exception to `onFailure` instead of
// letting it propagate (one bad key must not abort the rest of the batch). Returns whether the
// WHOLE batch can honestly be called clean: true only if every key ran without throwing.
//
// This is deliberately AND, not OR (the pre-fix behavior — see the fix report for how this was
// found: Jetcaster reproduced a real cycle where 15 of 41 keys threw
// `InvocationTargetException` from `invalidateGroupsWithKey`, including 100% of the keys for the
// exact class holding the edited composable, yet the batch still reported "tier1: remember state
// preserved" because a handful of OTHER keys in the same batch happened not to throw). Compose's
// own `invalidateGroupsWithKey` returns `Unit` whether a key matched a live group or matched
// nothing at all, so a clean call is not proof of a real invalidation either — but it is at least
// not proof of the opposite. A thrown exception IS proof: something about that specific key's
// invalidation did not run, so the composable it names cannot be assumed to have re-executed.
// One such proof anywhere in the batch is enough to withdraw the tier1 claim for the whole batch
// and let [ComposeInvalidator.reload] fall through to tier2 (whole-composition rebuild — loses
// `remember` state, but unconditionally re-executes every composable, so it cannot leave stale
// bytecode on screen the way a falsely-claimed tier1 did here).
//
// No Android imports: kept a free function (not a method on the Log-using, Handler-using
// ComposeInvalidator object) so it's a plain-JVM unit test target with no Robolectric/instrumented
// test needed — see ComposeInvalidatorTest.
internal fun invalidateAll(keys: List<Int>, invalidate: (Int) -> Unit, onFailure: (Int, Throwable) -> Unit): Boolean {
    var failures = 0
    for (key in keys) {
        try {
            invalidate(key)
        } catch (t: Throwable) {
            failures++
            onFailure(key, t)
        }
    }
    return failures == 0
}

// Every hook this file reaches into Compose with is called through `Method.invoke`, which wraps
// anything the target throws in an [InvocationTargetException] whose OWN message is null. Logging
// that wrapper printed the useless "InvocationTargetException: null" and discarded the only thing
// that identifies the failure, which is why the ComposableSingletons batch failures (see
// [invalidateAll]) stayed undiagnosed across several reload cycles: the tier report was honest
// that something threw, but never said what. Unwrap to the cause so the log names the real
// exception; keep the wrapper itself when there is no cause to unwrap to, since reporting it
// still beats reporting nothing.
//
// Free function, no Android imports, for the same plain-JVM-unit-test reason as [invalidateAll].
internal fun unwrapReflectionFailure(t: Throwable): Throwable =
    if (t is InvocationTargetException) t.cause ?: t else t

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
     * This runtime library's own version, generated at build time from `runtime/build.gradle.kts`
     * (`BuildConfig.HOTRELOAD_RUNTIME_VERSION` — see that file's `defaultConfig` for why it's
     * generated rather than a literal here). Called by the JVMTI agent via JNI on every PING (see
     * agent.cpp's `ReadRuntimeVersion`), so the CLI can refuse to proceed against a mismatched
     * runtime instead of silently no-op'ing a reload — see [reload]'s three-tier chain, which is
     * exactly what breaks invisibly when the CLI's LOAD_DEX wire format has moved on but this
     * method doesn't exist yet on an older, already-published runtime jar (agent.cpp's
     * `GetStaticMethodID` simply fails to find it, and reports "unknown" — see
     * `Protocol.UNKNOWN_RUNTIME_VERSION` on the CLI side for how that's treated as a warning, not
     * a hard failure).
     */
    @JvmStatic
    fun runtimeVersion(): String = BuildConfig.HOTRELOAD_RUNTIME_VERSION

    /**
     * Called by the JVMTI agent via JNI after RedefineClasses succeeds, once per LOAD_DEX batch,
     * with the binary names of every redefined class (e.g.
     * "dev.thuat.hotreload.sample.feature.GreetingKt") and the union of FunctionKeyMeta [keys]
     * the CLI already extracted host-side for them (may be empty — see below). Returns which
     * tier actually fired ("tier1"/"tier2"/"tier3"), or "tier-timeout" if the main-thread work
     * didn't finish within [REPLY_TIMEOUT_SECONDS] — so the CLI can surface the real state
     * guarantee to the user instead of just "reloaded".
     *
     * The invalidation work itself must run on the main thread (Compose's runtime hooks and
     * `Activity.recreate()` both require it), but this method is called from the agent's own
     * attached JNI thread (the socket server thread), not the main thread — so blocking here
     * on a latch signaled by the posted main-thread work cannot deadlock; they're always two
     * different threads.
     *
     * Three-tier fallback chain, tier taken always logged at [TAG]:
     *  1. Group-key invalidation (Live Edit's mechanism) — re-executes only the affected
     *     recompose scopes; preserves `remember` state. Uses [keys] as supplied by the CLI when
     *     present; only falls back to this class's own on-device [keysForClass] lookup when the
     *     CLI sent none (older CLI, or a case its host-side bytecode extraction missed) — see
     *     [keysForClass]'s doc for why that on-device lookup alone is no longer sufficient on
     *     Compose 1.11+, where `@FunctionKeyMeta` is BINARY-retention and applied directly to
     *     compiled methods instead of a reflectable holder class. Only claimed when EVERY key in
     *     the batch invalidated without throwing (see [invalidateAll]'s doc) — a batch with even
     *     one thrown exception falls through to tier 2 instead of reporting a false tier1
     *     (reproduced live against Jetcaster: some keys threw `InvocationTargetException` while
     *     others in the same batch didn't, and the pre-fix "any key succeeded" check reported
     *     tier1 for a reload that provably never re-executed the edited composable).
     *  2. Whole-composition rebuild via `HotReloader` reflection — loses `remember` state.
     *  3. `Activity.recreate()` — last resort when Compose's runtime hooks are unreachable.
     */
    @JvmStatic
    fun reload(binaryNames: Array<String>, keys: IntArray): String {
        val latch = CountDownLatch(1)
        var tier = "tier-timeout"
        mainHandler.post {
            try {
                val resolvedKeys = keys.toList().ifEmpty { binaryNames.flatMap(::keysForClass) }
                tier = when {
                    resolvedKeys.isNotEmpty() && invalidateGroupsWithKeys(resolvedKeys) -> {
                        Log.i(TAG, "tier1: group-key invalidation, keys=$resolvedKeys")
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

    // FALLBACK ONLY as of the host-side extraction fix (see [reload]'s doc): this on-device
    // reflective lookup is what [reload] uses when the CLI's `keys` array is empty. It still
    // works for Compose ~1.7, whose compiler option `generateFunctionKeyMetaClasses=true`
    // (enabled by the gradle plugin on debug builds) emits a sibling `<FileFacade>$KeyMeta`
    // class per *source file*, carrying one repeatable @FunctionKeyMeta(key, startOffset,
    // endOffset) per composable function or nested composable lambda declared anywhere in that
    // file — including composables that are members of a class, not just top-level ones — as a
    // RUNTIME-retention annotation on that holder CLASS, reflectable here.
    //
    // It does NOT work on Compose 1.11+: that compiler stopped emitting holder classes and
    // instead annotates each composable's own compiled method directly, but `@FunctionKeyMeta`
    // is declared `@Retention(AnnotationRetention.BINARY)` (verified via javap on
    // androidx.compose.runtime 1.11.4's own FunctionKeyMeta.class — see the fix report), so on
    // that version it lands in RuntimeInvisibleAnnotations and no amount of on-device reflection
    // can ever read it — only the CLI, reading the compiled .class file directly
    // (KeyMetaExtractor), can. This function is kept for the fallback case, not deleted.
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
        return invalidateAll(keys, invalidate) { key, t ->
            // Pass the throwable itself, not just its text: this is the batch failure that
            // silently costs the user their `remember` state by demoting the reload to tier2, and
            // the stack trace is what says whether Compose threw from inside the composition
            // traversal or somewhere else entirely. See [unwrapReflectionFailure].
            val real = unwrapReflectionFailure(t)
            Log.w(TAG, "invalidateGroupsWithKey($key) failed: ${real.javaClass.name}: ${real.message}", real)
        }
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
        // Unwrapped for the same reason as the tier1 path: when tier2 fails too the reload drops
        // all the way to Activity.recreate(), and "InvocationTargetException: null" explains none
        // of it. See [unwrapReflectionFailure].
        val real = unwrapReflectionFailure(t)
        Log.w(TAG, "HotReloader reflection failed: ${real.javaClass.name}: ${real.message}", real)
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

package dev.hotreload.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log

object ComposeInvalidator {
    private const val TAG = "HotReload"
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** Called by the JVMTI agent via JNI after RedefineClasses succeeds. */
    @JvmStatic
    fun reload() {
        mainHandler.post {
            if (!invalidateViaHotReloader()) {
                val activity = ActivityTracker.foreground
                if (activity != null) {
                    Log.w(TAG, "HotReloader unavailable; recreating ${activity.javaClass.simpleName}")
                    activity.recreate()
                } else {
                    Log.e(TAG, "Reload signalled but no foreground activity to refresh")
                }
            }
        }
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
}

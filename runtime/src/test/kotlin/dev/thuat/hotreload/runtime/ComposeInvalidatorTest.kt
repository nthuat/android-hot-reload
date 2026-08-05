package dev.thuat.hotreload.runtime

import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

// Covers invalidateAll in isolation (see its doc for why this is worth a dedicated test): the
// Jetcaster bug this closes was tier1 being reported honest-looking ("remember state preserved")
// for a batch where most keys' invalidation calls actually threw. The old "any key succeeded"
// check would have returned true for every case below except allFail and empty; the fixed
// "every key must succeed" check must return false the moment even one key throws.
class ComposeInvalidatorTest {
    @Test
    fun `all keys succeed`() {
        val invoked = mutableListOf<Int>()
        val result = invalidateAll(
            keys = listOf(1, 2, 3),
            invalidate = { invoked += it },
            onFailure = { _, _ -> error("should not be called") },
        )
        assertTrue(result)
        assertEquals(listOf(1, 2, 3), invoked)
    }

    @Test
    fun `one key throwing fails the whole batch`() {
        val failures = mutableListOf<Int>()
        val result = invalidateAll(
            keys = listOf(1, 2, 3),
            invalidate = { key -> if (key == 2) throw IllegalStateException("boom") },
            onFailure = { key, _ -> failures += key },
        )
        assertFalse(result, "a single thrown key must withdraw the tier1 claim for the batch")
        assertEquals(listOf(2), failures)
    }

    @Test
    fun `most keys throwing still fails the batch, matching the Jetcaster repro`() {
        // Mirrors the real reload: 15 of 41 keys threw, 26 didn't — the pre-fix "any succeeded"
        // check reported tier1 anyway. The fix must reject this the same as allFail.
        val keys = (1..41).toList()
        val throwingKeys = keys.take(15).toSet()
        val failures = mutableListOf<Int>()
        val result = invalidateAll(
            keys = keys,
            invalidate = { key -> if (key in throwingKeys) throw RuntimeException("InvocationTargetException") },
            onFailure = { key, _ -> failures += key },
        )
        assertFalse(result)
        assertEquals(15, failures.size)
    }

    @Test
    fun `every key throwing fails`() {
        val result = invalidateAll(
            keys = listOf(1, 2),
            invalidate = { throw RuntimeException("boom") },
            onFailure = { _, _ -> },
        )
        assertFalse(result)
    }

    @Test
    fun `empty key list is vacuously clean`() {
        // reload()'s caller already guards on resolvedKeys.isNotEmpty() before calling
        // invalidateGroupsWithKeys, but invalidateAll itself must not misreport an empty batch
        // as a failure if ever called directly.
        val result = invalidateAll(
            keys = emptyList(),
            invalidate = { error("should not be called") },
            onFailure = { _, _ -> error("should not be called") },
        )
        assertTrue(result)
    }

    @Test
    fun `unwraps the reflection wrapper so the real failure is what gets reported`() {
        // Every invalidateGroupsWithKey call goes through Method.invoke, which wraps whatever the
        // Compose runtime threw in an InvocationTargetException whose own message is null. Logging
        // the wrapper therefore printed "InvocationTargetException: null" and threw away the only
        // thing that explains the failure -- which is exactly why the ComposableSingletons tier2
        // fallback stayed undiagnosed. Unwrap so the cause is what reaches the log.
        val real = IllegalStateException("the actual Compose failure")
        assertEquals(real, unwrapReflectionFailure(InvocationTargetException(real)))
    }

    @Test
    fun `leaves a non-reflection exception alone`() {
        val direct = IllegalArgumentException("thrown directly")
        assertEquals(direct, unwrapReflectionFailure(direct))
    }

    @Test
    fun `keeps the wrapper when it has no cause`() {
        // Nothing useful to unwrap to; reporting the wrapper beats reporting null.
        val empty = InvocationTargetException(null)
        assertEquals(empty, unwrapReflectionFailure(empty))
    }

    @Test
    fun `failure callback receives the actual thrown exception`() {
        val seen = mutableListOf<Throwable>()
        val boom = IllegalArgumentException("specific cause")
        invalidateAll(
            keys = listOf(1),
            invalidate = { throw boom },
            onFailure = { _, t -> seen += t },
        )
        assertEquals(1, seen.size)
        assertEquals(boom, seen[0])
    }
}

package io.github.shweepsi.heystream.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enregistrement manuel : début par pas de 5 min alignés, durée bornée. */
class ManualWindowTest {
    private val now = 1_700_000_123L // pas sur un pas de 5 min

    @Test
    fun startsNowForAnHourByDefault() {
        val p = ManualWindow().programme("Manuel", now)
        assertEquals(now, p.start)
        assertEquals(now + 3600, p.stop)
    }

    @Test
    fun laterStartsAreAlignedOnFiveMinutes() {
        val w = ManualWindow().adjusted(start = true, delta = 1)
        val start = w.start(now)
        assertEquals(0L, start % MANUAL_START_STEP_SECS)
        assertTrue(start > now && start - now <= MANUAL_START_STEP_SECS)
        assertEquals(start + MANUAL_START_STEP_SECS, w.adjusted(true, 1).start(now))
        // Jamais avant maintenant.
        assertEquals(0, ManualWindow().adjusted(true, -1).startStep)
    }

    @Test
    fun durationStaysWithinBounds() {
        var w = ManualWindow()
        repeat(10) { w = w.adjusted(start = false, delta = -1) }
        assertEquals(MANUAL_DURATION_MIN_SECS, w.durationSecs)
        repeat(100) { w = w.adjusted(start = false, delta = 1) }
        assertEquals(MANUAL_DURATION_MAX_SECS, w.durationSecs)
    }
}

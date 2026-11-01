package io.github.shweepsi.heystream.ui.live

import io.github.shweepsi.heystream.player.TimeshiftPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeshiftModelsTest {
    private fun position(at: Double, playing: Boolean = true, eraseIn: Double? = null) =
        TimeshiftPosition(at = at, oldest = 1_000.0, newest = 2_800.0, playing = playing, eraseInSecs = eraseIn)

    @Test
    fun theBarShowsTheDelayAndTheAvailablePart() {
        val ui = timeshiftUi(position(2_050.0), pendingSeekSecs = null, eraseInSecs = null)
        assertEquals(750L, ui.behindSecs)
        assertEquals(1_800L, ui.availableSecs)
        assertFalse(ui.atLive)
        // Une demi-heure disponible sur la barre d'une heure, collée au direct.
        assertEquals(0.5f, ui.availableFraction, 0.001f)
        assertEquals(1f - 750f / 3600f, ui.cursorFraction, 0.001f)
        assertTrue(timeshiftUi(position(2_797.0), null, null).atLive)
    }

    @Test
    fun aLongPauseWarnsAMinuteBeforeItsMomentIsErased() {
        assertNull(timeshiftUi(position(1_010.0, playing = false), null, eraseInSecs = 90.0).resumeInSecs)
        assertEquals(42L, timeshiftUi(position(1_010.0, playing = false), null, eraseInSecs = 41.2).resumeInSecs)
        // En lecture, rien à annoncer.
        assertNull(timeshiftUi(position(1_010.0), null, eraseInSecs = 10.0).resumeInSecs)
        assertEquals(0L, timeshiftUi(position(1_010.0, playing = false), null, eraseInSecs = -3.0).resumeInSecs)
    }

    @Test
    fun jumpsStayInsideTheBuffer() {
        assertEquals(1_000.0, timeshiftTarget(position(1_005.0), -10), 0.0)
        assertEquals(2_800.0, timeshiftTarget(position(2_790.0), 30), 0.0)
        assertEquals(2_080.0, timeshiftTarget(position(2_050.0), 30), 0.0)
    }
}

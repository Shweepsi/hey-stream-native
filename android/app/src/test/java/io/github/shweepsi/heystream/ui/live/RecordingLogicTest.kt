package io.github.shweepsi.heystream.ui.live

import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.GuideProgramme
import io.github.shweepsi.heystream.core.Recording
import io.github.shweepsi.heystream.core.RecordingMargins
import io.github.shweepsi.heystream.core.RecordingState
import io.github.shweepsi.heystream.player.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enregistrement dans le Direct : logique pure, sans cœur natif. */
class RecordingLogicTest {
    private fun recording(
        id: Long,
        stream: Long,
        state: RecordingState,
        programmes: List<Long>,
        bytes: ULong = 0u,
        volumeMissing: Boolean = false,
    ) = Recording(
        id = id,
        streamId = stream,
        channel = "Chaîne $stream",
        isAdult = false,
        programmes = programmes.map { GuideProgramme("Programme $it", null, it, it + 3600) },
        start = programmes.first() - 120,
        stop = programmes.last() + 3600 + 300,
        state = state,
        problem = null,
        path = "/enregistrements/$id.ts",
        volumeMissing = volumeMissing,
        bytes = bytes,
        gapSecs = 0,
        startedAt = null,
        endedAt = null,
        positionMs = 0,
        durationMs = 0,
        watched = false,
    )

    @Test
    fun scheduledProgrammesOfTheChannelAreMarked() {
        val all = listOf(
            recording(1, 10, RecordingState.SCHEDULED, listOf(1_000, 4_600)),
            recording(2, 20, RecordingState.SCHEDULED, listOf(9_000)),
            recording(3, 10, RecordingState.DONE, listOf(100)),
        )
        assertEquals(setOf(1_000L, 4_600L), scheduledStarts(all, 10))
        assertEquals(setOf(9_000L), scheduledStarts(all, 20))
        assertEquals(1L, recordingFor(all, 10, LiveProgramme("Programme 4600", 4_600, 8_200))?.id)
        assertNull(recordingFor(all, 10, LiveProgramme("Programme 100", 100, 3_700)))
    }

    @Test
    fun marginsMoveByOneMinuteBetweenZeroAndOneHour() {
        val margins = RecordingMargins(beforeSecs = 120, afterSecs = 300)
        assertEquals(60L, margins.adjusted(before = true, delta = -MARGIN_STEP_SECS).beforeSecs)
        assertEquals(0L, margins.copy(beforeSecs = 0).adjusted(before = true, delta = -MARGIN_STEP_SECS).beforeSecs)
        assertEquals(360L, margins.adjusted(before = false, delta = MARGIN_STEP_SECS).afterSecs)
        assertEquals(MARGIN_MAX_SECS, margins.copy(afterSecs = MARGIN_MAX_SECS).adjusted(before = false, delta = MARGIN_STEP_SECS).afterSecs)
    }

    @Test
    fun onlyRecordingsWithAFileCanBeWatched() {
        assertTrue(recording(1, 10, RecordingState.RECORDING, listOf(1)).toRow().playable)
        assertTrue(recording(2, 10, RecordingState.INCOMPLETE, listOf(1), bytes = 5u).toRow().playable)
        assertFalse(recording(3, 10, RecordingState.SCHEDULED, listOf(1)).toRow().playable)
        assertFalse(recording(4, 10, RecordingState.FAILED, listOf(1)).toRow().playable)
        assertFalse(recording(5, 10, RecordingState.DONE, listOf(1), bytes = 5u, volumeMissing = true).toRow().playable)
        val merged = recording(6, 10, RecordingState.DONE, listOf(1, 3_601, 7_201), bytes = 5u).toRow()
        assertEquals("Programme 1", merged.title)
        assertEquals(2, merged.more)
    }

    @Test
    fun emptyRecordingsAreHiddenUnlessOpen() {
        val provider = listOf(CategoryEntry(id = "1", name = "Infos", isAdult = false, locked = false, hidden = false))
        val categories = liveCategories(provider, mapOf("1" to 3L), favorites = 0, recents = 0, recordings = 0)
        assertFalse(shownCategories(categories, CategoryKey.Provider("1")).any { it.key == CategoryKey.Recordings })
        assertTrue(shownCategories(categories, CategoryKey.Recordings).any { it.key == CategoryKey.Recordings })
        val some = liveCategories(provider, mapOf("1" to 3L), favorites = 0, recents = 0, recordings = 1)
        assertTrue(shownCategories(some, CategoryKey.Provider("1")).any { it.key == CategoryKey.Recordings })
    }

    @Test
    fun aRecordingInProgressBlocksOtherStreams() {
        val blocked = PlaybackStatus(active = true, live = true, blocked = true)
        assertEquals(LivePlayback.Blocked, livePlaybackOf(blocked, hasPreview = true, previewError = null))
        assertEquals(LivePlayback.Idle, livePlaybackOf(blocked, hasPreview = false, previewError = null))
    }
}

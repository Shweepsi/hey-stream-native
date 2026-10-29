package io.github.shweepsi.heystream.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.ParentalFakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.Opens
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.player.advanceMainUntil
import io.github.shweepsi.heystream.player.runMainUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** Appuis d'avance et de recul rapprochés : cumulés, puis un seul saut. */
@RunWith(AndroidJUnit4::class)
class PlayerSeekTest {
    private val core = ParentalFakeCore().apply { add(Section.MOVIES, 7, "Film") }
    private val controller = PlayerController(RuntimeEnvironment.getApplication())
    private val opens = Opens().also { controller.player.addListener(it) }

    @After
    fun release() {
        controller.release()
    }

    @Test
    fun closePressesAddUpThenApplyOnce() {
        val handle = PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, 7, seriesId = null, fromStart = false)
        val viewModel = PlayerViewModel(fakeSession(core), controller, handle)
        runMainUntil { opens.count == 1 }

        repeat(3) { viewModel.nudge(forward = true) }
        viewModel.nudge(forward = false)
        assertEquals(3 * FORWARD_STEP_MS - BACK_STEP_MS, viewModel.state.value.pendingSeekMs)

        // Plus d'appui : le saut cumulé part, l'affichage disparaît.
        advanceMainUntil { viewModel.state.value.pendingSeekMs == null }
        assertNull(viewModel.state.value.pendingSeekMs)
    }
}

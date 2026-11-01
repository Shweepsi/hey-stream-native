package io.github.shweepsi.heystream.ui.live

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.RecordingState
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.ParentalFakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.player.runMainUntil
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * Enregistrement ouvert depuis le Direct : l'écran du Direct disparaît
 * après que le lecteur d'enregistrement a commencé ; il ne doit pas arrêter
 * cette lecture (bouton Lecture sans effet sinon).
 */
@RunWith(AndroidJUnit4::class)
class RecordingHandOverTest {
    private val core = ParentalFakeCore().apply { add(Section.LIVE, 1L, "Info continue") }
    private val session = fakeSession(core)
    private val controller = PlayerController(RuntimeEnvironment.getApplication())

    @After
    fun release() {
        controller.release()
    }

    @Test
    fun leavingLiveKeepsTheRecordingPlaying() {
        val viewModel = LiveViewModel(session, controller, initialChannelId = null)
        runMainUntil { viewModel.state.value.categories is CategoriesUi.Ready }
        val row = RecordingRow(
            id = 7, streamId = 1, title = "Journal", more = 0, channel = "Info continue",
            state = RecordingState.DONE, problem = null, start = 0, stop = 3_600, bytes = 1_000,
            volumeMissing = false, watched = false, progress = null,
        )
        viewModel.onRecordingClick(row)
        assertEquals(7L, viewModel.openRecording.value)
        // Le lecteur d'enregistrement démarre…
        controller.playRecording("/absent/7.ts")
        assertTrue(controller.status.value.active)
        // … puis l'écran du Direct quitte la composition.
        viewModel.onLeave()
        assertTrue(controller.status.value.active)
        viewModel.viewModelScope.cancel()
    }
}

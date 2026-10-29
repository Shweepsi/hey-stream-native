package io.github.shweepsi.heystream.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.FakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.Opens
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.player.runMainUntil
import io.github.shweepsi.heystream.player.settleMain
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * Catégories adultes déverrouillées, un film en cours : l'app quittée les
 * reverrouille. Au retour, un film adulte n'est pas rouvert et l'écran se
 * ferme ; un film tout public reprend comme avant. Le reverrouillage est
 * retenu dans le faux cœur : l'app revient avant qu'il ne soit posé.
 */
@RunWith(AndroidJUnit4::class)
class PlayerRelockTest {
    private val core = FakeCore().apply {
        add(Section.MOVIES, ADULT, "Film adulte", adult = true)
        add(Section.MOVIES, PUBLIC, "Film tout public")
    }
    private val session = fakeSession(core)
    private val controller = PlayerController(RuntimeEnvironment.getApplication())
    private val opens = Opens().also { controller.player.addListener(it) }

    @After
    fun release() {
        controller.release()
    }

    private fun openFilm(id: Long): PlayerViewModel {
        val handle = PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, id, seriesId = null, fromStart = false)
        val viewModel = PlayerViewModel(session, controller, handle)
        runMainUntil { opens.count == 1 }
        return viewModel
    }

    /** Comme `MainActivity.onStop`, après l'observateur ON_STOP de l'écran. */
    private fun leaveApp(viewModel: PlayerViewModel) {
        core.relockGate = CountDownLatch(1)
        viewModel.onBackground()
        controller.onBackground()
        session.lockParental()
        controller.hold()
    }

    /** Comme `MainActivity.onStart`, puis l'observateur ON_START de l'écran. */
    private fun returnToApp(viewModel: PlayerViewModel) {
        controller.onForeground()
        viewModel.onForeground()
    }

    @Test
    fun adultFilmIsNotReopenedAndTheScreenCloses() {
        val viewModel = openFilm(ADULT)
        assertEquals("Film adulte", viewModel.state.value.title)
        leaveApp(viewModel)
        returnToApp(viewModel)
        settleMain()
        // Retour avant la fin du reverrouillage : rien n'est rouvert.
        assertEquals(1, opens.count)

        checkNotNull(core.relockGate).countDown()
        runMainUntil { viewModel.state.value.exit }
        settleMain()
        assertEquals(1, opens.count)
        assertEquals(PlaybackStatus(), controller.status.value)
        assertEquals("", viewModel.state.value.title)
    }

    @Test
    fun publicFilmResumesOnceTheLockIsBack() {
        val viewModel = openFilm(PUBLIC)
        leaveApp(viewModel)
        returnToApp(viewModel)
        settleMain()
        assertEquals(1, opens.count)

        checkNotNull(core.relockGate).countDown()
        runMainUntil { opens.count == 2 }
        assertTrue(controller.player.playWhenReady)
        assertFalse(viewModel.state.value.exit)
        assertEquals("Film tout public", viewModel.state.value.title)
    }

    @Test
    fun pausedPublicFilmReopensPaused() {
        val viewModel = openFilm(PUBLIC)
        controller.player.pause()
        leaveApp(viewModel)
        checkNotNull(core.relockGate).countDown()
        // Vérification finie en arrière-plan : le retour rouvre aussitôt, en pause.
        runMainUntil { !controller.held.value }
        assertEquals(1, opens.count)
        returnToApp(viewModel)
        assertEquals(2, opens.count)
        assertFalse(controller.player.playWhenReady)
        assertFalse(viewModel.state.value.exit)
    }

    private companion object {
        const val ADULT = 42L
        const val PUBLIC = 7L
    }
}

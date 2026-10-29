package io.github.shweepsi.heystream.ui.live

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.FakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.Opens
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
 * Direct, chaîne en plein écran dans une catégorie déverrouillée : l'app
 * quittée reverrouille. Au retour, une chaîne adulte disparaît sans être
 * rouverte ; une autre reprend. L'app revient avant la fin du reverrouillage.
 */
@RunWith(AndroidJUnit4::class)
class LiveRelockTest {
    private val core = FakeCore().apply {
        add(Section.LIVE, ADULT, "Chaîne adulte", adult = true)
        add(Section.LIVE, NEWS, "Info continue")
    }
    private val session = fakeSession(core)
    private val controller = PlayerController(RuntimeEnvironment.getApplication())
    private val opens = Opens().also { controller.player.addListener(it) }

    @After
    fun release() {
        controller.release()
    }

    /** Chaîne ouverte en plein écran (comme depuis la recherche). */
    private fun watch(id: Long): LiveViewModel {
        val viewModel = LiveViewModel(session, controller, initialChannelId = id)
        runMainUntil { opens.count == 1 && viewModel.state.value.fullscreen }
        assertEquals(id, viewModel.state.value.preview?.id)
        return viewModel
    }

    /** Comme `MainActivity.onStop` puis `onStart`, le reverrouillage pas encore posé. */
    private fun leaveAndReturn() {
        core.relockGate = CountDownLatch(1)
        controller.onBackground()
        session.lockParental()
        controller.hold()
        controller.onForeground()
        settleMain()
    }

    @Test
    fun adultChannelIsDroppedWithoutReopening() {
        val viewModel = watch(ADULT)
        leaveAndReturn()
        assertEquals(1, opens.count)

        checkNotNull(core.relockGate).countDown()
        runMainUntil { viewModel.state.value.preview == null }
        settleMain()
        assertEquals(1, opens.count)
        assertFalse(viewModel.state.value.fullscreen)
        assertFalse(controller.status.value.active)
        assertFalse(controller.held.value)
    }

    @Test
    fun otherChannelResumesOnceTheLockIsBack() {
        val viewModel = watch(NEWS)
        leaveAndReturn()
        assertEquals(1, opens.count)

        checkNotNull(core.relockGate).countDown()
        runMainUntil { opens.count == 2 }
        assertEquals(NEWS, viewModel.state.value.preview?.id)
        assertTrue(viewModel.state.value.fullscreen)
        assertTrue(controller.player.playWhenReady)
    }

    private companion object {
        const val ADULT = 7L
        const val NEWS = 3L
    }
}

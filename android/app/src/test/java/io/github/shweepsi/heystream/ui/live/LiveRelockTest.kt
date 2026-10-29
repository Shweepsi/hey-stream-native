package io.github.shweepsi.heystream.ui.live

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.ParentalFakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.Opens
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.player.runMainUntil
import io.github.shweepsi.heystream.player.settleMain
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val core = ParentalFakeCore().apply {
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

    /** Comme `MainActivity.onStop`. */
    private fun leave() {
        controller.onBackground()
        session.lockParental()
        controller.hold()
    }

    /** Comme `MainActivity.onStop` puis `onStart`, le reverrouillage pas encore posé. */
    private fun leaveAndReturn() {
        core.relockGate = CountDownLatch(1)
        leave()
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
        assertEquals(0L, controller.held.value)
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

    @Test
    fun adultChannelOpenedAgainBeforeTheRelockLandsIsDropped() {
        val first = watch(ADULT)
        leaveAndReturn()
        // Retour : le direct se ferme ; la chaîne est rouverte depuis la
        // recherche avant la fin du reverrouillage (encore visible).
        first.onLeave()
        first.viewModelScope.cancel()
        val again = LiveViewModel(session, controller, initialChannelId = ADULT)
        runMainUntil { again.state.value.preview != null }
        settleMain()
        assertEquals(1, opens.count)

        checkNotNull(core.relockGate).countDown()
        runMainUntil { again.state.value.preview == null }
        settleMain()
        assertEquals(1, opens.count)
        assertEquals(0L, controller.held.value)
    }

    @Test
    fun leavingAgainBeforeTheCheckWaitsForTheLatestRelock() {
        val viewModel = watch(ADULT)
        val firstRelock = CountDownLatch(1)
        core.relockGate = firstRelock
        leave()
        controller.onForeground()
        runMainUntil { core.relocksStarted.get() == 1 }
        // L'app repart avant la fin du premier reverrouillage.
        val secondRelock = CountDownLatch(1)
        core.relockGate = secondRelock
        leave()
        // Le premier se pose, puis le code saisi au retour (catégorie adulte
        // rouverte) déverrouille, avant le second reverrouillage.
        firstRelock.countDown()
        val end = System.currentTimeMillis() + 5_000
        while (core.unlocked) {
            check(System.currentTimeMillis() < end) { "Premier reverrouillage jamais posé" }
            Thread.sleep(2)
        }
        core.unlocked = true
        runMainUntil { core.relocksStarted.get() == 2 }
        settleMain()
        // Retour avant la fin du second : rien n'est rouvert.
        controller.onForeground()
        settleMain()
        assertEquals(1, opens.count)

        secondRelock.countDown()
        runMainUntil { viewModel.state.value.preview == null }
        settleMain()
        assertEquals(1, opens.count)
    }

    @Test
    fun adultChannelPickedBeforeTheListReloadsIsNotOpened() {
        val viewModel = watch(NEWS)
        leave()
        controller.onForeground()
        runMainUntil { opens.count == 2 }
        // La liste montre encore la chaîne adulte (pas encore relue) : choisie, elle ne s'ouvre pas.
        viewModel.onChannelClick(checkNotNull(core.items[Section.LIVE to ADULT]))
        settleMain()
        assertEquals(2, opens.count)
        assertNull(viewModel.state.value.preview)
    }

    private companion object {
        const val ADULT = 7L
        const val NEWS = 3L
    }
}

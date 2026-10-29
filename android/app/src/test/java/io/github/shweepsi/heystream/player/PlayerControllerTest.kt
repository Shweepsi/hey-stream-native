package io.github.shweepsi.heystream.player

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Lecteur réel (Media3) sous Robolectric, sur des fichiers absents : chaque
 * ouverture échoue aussitôt, sans réseau, et déclenche la reconnexion.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControllerTest {
    private val controller = PlayerController(RuntimeEnvironment.getApplication())
    private val opens = Opens().also { controller.player.addListener(it) }

    @After
    fun release() {
        controller.release()
    }

    /** Première coupure, puis la nouvelle tentative 1 s plus tard. */
    private fun failThenRetry() {
        advanceMainUntil { controller.status.value.reconnectAttempt == 1 }
        val before = opens.count
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertEquals(before + 1, opens.count)
    }

    @Test
    fun automaticRetryKeepsAPausedFilmPaused() {
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        controller.player.pause()
        failThenRetry()
        assertFalse(controller.player.playWhenReady)
    }

    @Test
    fun automaticRetryKeepsAPlayingFilmPlaying() {
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        failThenRetry()
        assertTrue(controller.player.playWhenReady)
    }

    @Test
    fun automaticRetryAlwaysPlaysLive() {
        controller.playLive("file:///absent/live.ts")
        controller.player.pause()
        failThenRetry()
        assertTrue(controller.player.playWhenReady)
    }

    @Test
    fun retryButtonPlaysAPausedFilm() {
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        controller.player.pause()
        controller.retry()
        assertTrue(controller.player.playWhenReady)
    }

    @Test
    fun heldStreamWaitsForItsScreenOnReturn() {
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        assertEquals(1, opens.count)
        controller.onBackground()
        controller.hold()
        controller.onForeground()
        assertEquals(1, opens.count)
        // « Réessayer » non plus ne rouvre rien avant la vérification.
        controller.retry()
        assertEquals(1, opens.count)
        controller.releaseHold()
        assertEquals(2, opens.count)
        assertTrue(controller.player.playWhenReady)
    }

    @Test
    fun streamReleasedInTheBackgroundReopensOnReturn() {
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        controller.player.pause()
        controller.onBackground()
        controller.hold()
        controller.releaseHold()
        assertEquals(1, opens.count)
        controller.onForeground()
        // En pause avant l'arrière-plan : rouvert en pause.
        assertEquals(2, opens.count)
        assertFalse(controller.player.playWhenReady)
    }

    @Test
    fun streamRequestedWhileHeldOpensOnlyOnRelease() {
        controller.onBackground()
        controller.hold()
        controller.onForeground()
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        assertEquals(0, opens.count)
        controller.releaseHold()
        assertEquals(1, opens.count)
    }

    @Test
    fun stoppingKeepsTheHold() {
        controller.onBackground()
        controller.hold()
        controller.onForeground()
        // Écran fermé puis titre relancé avant la vérification : rien ne s'ouvre.
        controller.stop()
        controller.playVod("file:///absent/film.mkv", startMs = 0)
        assertEquals(0, opens.count)
        controller.releaseHold()
        assertEquals(1, opens.count)
    }

    @Test
    fun heldRequestKeepsItsPositionWhenTheAppLeavesAgain() {
        controller.onBackground()
        controller.hold()
        controller.onForeground()
        controller.playVod("file:///absent/film.mkv", startMs = 60_000)
        // L'app repart avant la vérification : le titre demandé n'a jamais été ouvert.
        controller.onBackground()
        controller.releaseHold()
        assertEquals(0, opens.count)
        controller.onForeground()
        assertEquals(1, opens.count)
        assertEquals(60_000L, controller.player.currentPosition)
        assertTrue(controller.player.playWhenReady)
    }
}

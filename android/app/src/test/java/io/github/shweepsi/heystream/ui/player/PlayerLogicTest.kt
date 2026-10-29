package io.github.shweepsi.heystream.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Titre gardé dans le handle de l'écran (arrêt de l'app), écran allumé. */
@RunWith(AndroidJUnit4::class)
class PlayerLogicTest {
    /** Ce que la navigation met dans le handle : les arguments de `Routes.PLAYER`. */
    private fun routeHandle(kind: PlaybackKind, id: Long, seriesId: Long, fromStart: Boolean) = SavedStateHandle(
        mapOf(PlayerArgs.KIND to kind.name, PlayerArgs.ID to id, PlayerArgs.SERIES_ID to seriesId, PlayerArgs.FROM_START to fromStart),
    )

    /**
     * Arrêt de l'app puis retour : le handle revient avec ce qu'il contenait,
     * et l'écran le remplit à nouveau avec les arguments **d'origine** de la route.
     */
    private fun restore(handle: SavedStateHandle, kind: PlaybackKind, id: Long, seriesId: Long?, fromStart: Boolean): PlayerArgs {
        val saved = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        return PlayerArgs(PlayerArgs.seed(saved, kind, id, seriesId, fromStart))
    }

    @Test
    fun routeArgumentsFillAnEmptyHandle() {
        val args = PlayerArgs(PlayerArgs.seed(SavedStateHandle(), PlaybackKind.EPISODE, 21, seriesId = 5, fromStart = true))
        assertEquals(PlaybackKind.EPISODE, args.kind)
        assertEquals(21L, args.id)
        assertEquals(5L, args.seriesId)
        assertTrue(args.fromStart)
    }

    @Test
    fun aMovieHasNoSeries() {
        // La route donne -1 sans série.
        val args = PlayerArgs(PlayerArgs.seed(routeHandle(PlaybackKind.MOVIE, 42, -1, false), PlaybackKind.MOVIE, 42, null, false))
        assertEquals(PlaybackKind.MOVIE, args.kind)
        assertEquals(42L, args.id)
        assertNull(args.seriesId)
        assertFalse(args.fromStart)
    }

    @Test
    fun startPositionFollowsTheRouteOnFirstOpening() {
        // « Reprendre » : la reprise du cœur ; « Depuis le début » : 0.
        assertNull(PlayerArgs(PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, 42, null, false)).startMs)
        assertEquals(0L, PlayerArgs(PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, 42, null, true)).startMs)
    }

    @Test
    fun aRestoredFromStartResumesWhereTheScreenWasNotAtZero() {
        // « Depuis le début » sur un film (ancienne reprise à 50:00 dans le cœur),
        // regardé jusqu'à 40:00, puis l'app est arrêtée en arrière-plan.
        val handle = PlayerArgs.seed(routeHandle(PlaybackKind.MOVIE, 42, -1, true), PlaybackKind.MOVIE, 42, null, true)
        val args = PlayerArgs(handle)
        args.reached(2_400_000)

        // Au retour, la route dit toujours « depuis le début » : le handle l'emporte,
        // la lecture reprend à 40:00 (et la reprise n'est pas écrasée par un départ à 0).
        val restored = restore(handle, PlaybackKind.MOVIE, 42, null, fromStart = true)
        assertEquals(PlaybackKind.MOVIE, restored.kind)
        assertEquals(42L, restored.id)
        assertEquals(2_400_000L, restored.startMs)
    }

    @Test
    fun aRestoredFromStartUnder30sDoesNotJumpToTheOldResumePoint() {
        // « Depuis le début », arrêtée au bout de 20 s : le cœur, qui ne retient rien
        // sous 30 s, a toujours l'ancienne reprise (50:00). L'écran reprend à 0:20.
        val handle = PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, 42, null, true)
        PlayerArgs(handle).reached(20_000)
        assertEquals(20_000L, restore(handle, PlaybackKind.MOVIE, 42, null, fromStart = true).startMs)
    }

    @Test
    fun fromStartStillAppliesIfNothingWasRecorded() {
        // Arrêtée avant toute position (fiche ou flux en chargement, app en arrière-plan) :
        // rien n'a été lu, « Depuis le début » vaut toujours.
        val handle = PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, 42, null, true)
        val restored = restore(handle, PlaybackKind.MOVIE, 42, null, fromStart = true)
        assertTrue(restored.fromStart)
        assertEquals(0L, restored.startMs)
    }

    @Test
    fun aRestoredScreenReopensTheEpisodeReachedByAutoAdvance() {
        // Épisode 21 lancé depuis la fiche, puis 22 et 23 enchaînés.
        val handle = PlayerArgs.seed(routeHandle(PlaybackKind.EPISODE, 21, 5, false), PlaybackKind.EPISODE, 21, 5, false)
        val args = PlayerArgs(handle)
        args.reached(2_000_000)
        args.advanceTo(22)
        args.reached(2_100_000)
        args.advanceTo(23)
        assertEquals(23L, args.id)
        assertFalse(args.fromStart)
        // La position atteinte était celle du 22 : le 23 s'ouvre à sa reprise.
        assertNull(args.startMs)

        // La route nomme toujours l'épisode 21 (vu, sans reprise) : c'est le 23 qui revient.
        val restored = restore(handle, PlaybackKind.EPISODE, 21, 5, fromStart = false)
        assertEquals(PlaybackKind.EPISODE, restored.kind)
        assertEquals(23L, restored.id)
        assertEquals(5L, restored.seriesId)
        assertNull(restored.startMs)
        // Regardé un peu, puis arrêtée : le 23 reprend là.
        PlayerArgs(handle).reached(600_000)
        assertEquals(600_000L, restore(handle, PlaybackKind.EPISODE, 21, 5, fromStart = false).startMs)
    }

    @Test
    fun autoAdvanceNeverStartsTheNextEpisodeFromZero() {
        // « Depuis le début » sur l'épisode 21 ne s'applique pas au suivant.
        val args = PlayerArgs(PlayerArgs.seed(SavedStateHandle(), PlaybackKind.EPISODE, 21, 5, true))
        args.advanceTo(22)
        assertFalse(args.fromStart)
        assertNull(args.startMs)
    }

    @Test
    fun screenStaysOnOnlyWhilePlayingOrLoading() {
        val ready = PlayerUiState(title = "Nord Express", resolving = false)
        assertTrue(keepsScreenOn(ready, PlaybackStatus(active = true, playing = true)))
        // Chargement et reconnexion : l'image va revenir.
        assertTrue(keepsScreenOn(ready, PlaybackStatus(active = true, buffering = true)))
        assertTrue(keepsScreenOn(ready, PlaybackStatus(active = true, buffering = true, reconnectAttempt = 2)))
        // Pause, fin, échec, flux introuvable : la mise en veille reste possible.
        assertFalse(keepsScreenOn(ready, PlaybackStatus(active = true)))
        assertFalse(keepsScreenOn(ready, PlaybackStatus(active = true, ended = true)))
        assertFalse(keepsScreenOn(ready, PlaybackStatus(active = true, failed = true)))
        assertFalse(keepsScreenOn(ready.copy(error = UiText(R.string.error_network)), PlaybackStatus()))
        assertFalse(keepsScreenOn(ready.copy(error = UiText(R.string.error_network)), PlaybackStatus(active = true, buffering = true)))
    }
}

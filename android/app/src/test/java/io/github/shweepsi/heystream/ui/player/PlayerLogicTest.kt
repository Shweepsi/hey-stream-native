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
    fun fromStartAppliesOnlyUntilTheStreamIsOpened() {
        // « Depuis le début » sur un film, regardé, puis l'app est arrêtée en arrière-plan.
        val handle = PlayerArgs.seed(routeHandle(PlaybackKind.MOVIE, 42, -1, true), PlaybackKind.MOVIE, 42, null, true)
        val args = PlayerArgs(handle)
        assertTrue(args.fromStart)
        args.started()

        // Au retour, la route dit toujours « depuis le début » : le handle l'emporte,
        // la lecture reprend à la position enregistrée (elle n'est pas écrasée).
        val restored = restore(handle, PlaybackKind.MOVIE, 42, null, fromStart = true)
        assertEquals(PlaybackKind.MOVIE, restored.kind)
        assertEquals(42L, restored.id)
        assertFalse(restored.fromStart)
    }

    @Test
    fun fromStartStillAppliesIfTheStreamWasNeverOpened() {
        // Arrêtée avant l'ouverture du flux (fiche en chargement, app en arrière-plan) :
        // rien n'a été lu, « Depuis le début » vaut toujours.
        val handle = PlayerArgs.seed(SavedStateHandle(), PlaybackKind.MOVIE, 42, null, true)
        assertTrue(restore(handle, PlaybackKind.MOVIE, 42, null, fromStart = true).fromStart)
    }

    @Test
    fun aRestoredScreenReopensTheEpisodeReachedByAutoAdvance() {
        // Épisode 21 lancé depuis la fiche, puis 22 et 23 enchaînés.
        val handle = PlayerArgs.seed(routeHandle(PlaybackKind.EPISODE, 21, 5, false), PlaybackKind.EPISODE, 21, 5, false)
        val args = PlayerArgs(handle)
        args.started()
        args.advanceTo(22)
        args.started()
        args.advanceTo(23)
        assertEquals(23L, args.id)
        assertFalse(args.fromStart)

        // La route nomme toujours l'épisode 21 (vu, sans reprise) : c'est le 23 qui revient.
        val restored = restore(handle, PlaybackKind.EPISODE, 21, 5, fromStart = false)
        assertEquals(PlaybackKind.EPISODE, restored.kind)
        assertEquals(23L, restored.id)
        assertEquals(5L, restored.seriesId)
        assertFalse(restored.fromStart)
    }

    @Test
    fun autoAdvanceNeverStartsTheNextEpisodeFromZero() {
        // « Depuis le début » sur l'épisode 21 ne s'applique pas au suivant.
        val args = PlayerArgs(PlayerArgs.seed(SavedStateHandle(), PlaybackKind.EPISODE, 21, 5, true))
        args.advanceTo(22)
        assertFalse(args.fromStart)
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

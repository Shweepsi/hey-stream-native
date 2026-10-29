package io.github.shweepsi.heystream.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Télécommande dans le lecteur : OK, gauche / droite, haut / bas, épisode suivant. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class PlayerKeysTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder : PlayerActions {
        var toggles = 0
        val seeks = mutableListOf<Long>()
        var nextPlayed = 0

        override fun togglePause() {
            toggles++
        }

        override fun seekBy(deltaMs: Long) {
            seeks += deltaMs
        }

        override fun seekTo(fraction: Float) = Unit

        override fun selectTrack(key: TrackKey) = Unit

        override fun disableSubtitles() = Unit

        override fun retry() = Unit

        override fun playNext() {
            nextPlayed++
        }

        override fun cancelNext() = Unit

        override fun exit() = Unit
    }

    private val keyboard = object : InputModeManager {
        override val inputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
    }

    private fun show(chrome: PlayerChrome, actions: Recorder, state: PlayerUiState = PlayerUiState(title = "Nord Express", resolving = false)) {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides keyboard) {
                HeyStreamTheme(isTv = true, systemDark = true) {
                    PlayerContent(
                        state = state,
                        status = PlaybackStatus(active = true, playing = true),
                        position = PlaybackPosition(60_000, 600_000),
                        chrome = chrome,
                        actions = actions,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun okPausesOnceAndShowsTheControls() {
        val chrome = PlayerChrome(visible = false)
        val actions = Recorder()
        show(chrome, actions)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, actions.toggles)
        assertTrue(chrome.visible)
        // OK à nouveau : le bouton lecture / pause a le focus.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(2, actions.toggles)
    }

    @Test
    fun leftAndRightSeekEvenWhenTheControlsAreHidden() {
        val chrome = PlayerChrome(visible = false)
        val actions = Recorder()
        show(chrome, actions)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(listOf(PlayerController.SEEK_STEP_MS), actions.seeks)
        assertTrue(chrome.visible)
        assertEquals(ControlFocus.Seek, chrome.focus)
        // La barre a maintenant le focus : gauche recule.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(listOf(PlayerController.SEEK_STEP_MS, -PlayerController.SEEK_STEP_MS), actions.seeks)
        assertEquals(0, actions.toggles)
    }

    @Test
    fun upShowsTheControlsWithoutActing() {
        val chrome = PlayerChrome(visible = false)
        val actions = Recorder()
        show(chrome, actions)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertTrue(chrome.visible)
        assertEquals(0, actions.toggles)
        assertTrue(actions.seeks.isEmpty())
    }

    @Test
    fun okOnTheNextEpisodeCardPlaysItNow() {
        val chrome = PlayerChrome(visible = false)
        val actions = Recorder()
        val state = PlayerUiState(
            title = "Les Veilleurs du large",
            resolving = false,
            next = NextEpisodeUi(EpisodeLabel(2, 4, "Marée haute"), secondsLeft = 9),
        )
        show(chrome, actions, state)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, actions.nextPlayed)
        assertEquals(0, actions.toggles)
        assertFalse(chrome.visible)
    }
}

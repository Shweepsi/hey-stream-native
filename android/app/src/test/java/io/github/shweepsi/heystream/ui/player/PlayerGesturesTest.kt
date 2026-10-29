package io.github.shweepsi.heystream.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lecteur au doigt : double tap sur un côté, glissements de volume et de luminosité. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w915dp-h412dp-land-xxhdpi")
class PlayerGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder : PlayerActions {
        val nudges = mutableListOf<Boolean>()

        override fun togglePause() = Unit

        override fun nudge(forward: Boolean) {
            nudges += forward
        }

        override fun seekTo(fraction: Float) = Unit

        override fun selectTrack(key: TrackKey) = Unit

        override fun disableSubtitles() = Unit

        override fun retry() = Unit

        override fun playNext() = Unit

        override fun cancelNext() = Unit

        override fun exit() = Unit
    }

    private class Levels : PlayerLevels {
        override var volume = 0.5f
        override var brightness = 0.5f
    }

    private val actions = Recorder()
    private val levels = Levels()

    private val chrome = PlayerChrome(visible = false)

    private fun show(state: PlayerUiState = PlayerUiState(title = "Nord Express", resolving = false)) {
        compose.setContent {
            HeyStreamTheme(isTv = false) {
                PlayerContent(
                    state = state,
                    status = PlaybackStatus(active = true, playing = true),
                    position = PlaybackPosition(60_000, 600_000),
                    // Commandes masquées : la vidéo reçoit les gestes.
                    chrome = chrome,
                    actions = actions,
                    levels = levels,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun doubleTapOnASideNudgesThatWay() {
        show()
        compose.onRoot().performTouchInput { doubleClick(Offset(width * 0.9f, centerY)) }
        compose.onRoot().performTouchInput { doubleClick(Offset(width * 0.1f, centerY)) }
        compose.waitForIdle()
        assertEquals(listOf(true, false), actions.nudges.take(2))
    }

    @Test
    fun duringAJumpEachTapOnASideCountsAtOnce() {
        show(PlayerUiState(title = "Nord Express", resolving = false, pendingSeekMs = 30_000))
        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, centerY)) }
        compose.onRoot().performTouchInput { click(Offset(width * 0.9f, centerY)) }
        // Pas d'attente du double tap, pas de commandes montrées.
        assertEquals(listOf(true, true), actions.nudges)
        assertEquals(false, chrome.visible)
    }

    @Test
    fun swipingUpOnTheRightRaisesTheVolumeAndOnTheLeftTheBrightness() {
        show()
        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.7f), Offset(width * 0.8f, height * 0.3f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue("volume ${levels.volume}", levels.volume > 0.9f)
        assertEquals(0.5f, levels.brightness)

        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.2f, height * 0.3f), Offset(width * 0.2f, height * 0.6f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue("luminosité ${levels.brightness}", levels.brightness < 0.2f)
    }
}

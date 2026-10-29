package io.github.shweepsi.heystream.ui.detail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fiches à la télécommande : premier bouton, appui long et MENU sur un épisode. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DetailKeysTest {
    @get:Rule
    val compose = createComposeRule()

    private val keyboard = object : InputModeManager {
        override val inputMode = InputMode.Keyboard

        override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides keyboard) {
                HeyStreamTheme(isTv = true, content = content)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun okOnOpenResumesTheMovie() {
        val plays = mutableListOf<Boolean>()
        show {
            MovieDetailContent(DetailFakes.startedMovie, onPlay = { plays += it }, onToggleFavorite = {}, onToggleWatched = {}, onRetry = {}, onBack = {})
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf(false), plays)
    }

    private class SeriesCalls {
        val played = mutableListOf<Pair<Long, Boolean>>()
        val toggled = mutableListOf<Long>()
    }

    private fun showSeries(calls: SeriesCalls, state: SeriesDetailState = DetailFakes.series) = show {
        SeriesDetailContent(
            state = state,
            onPlayEpisode = { id, fromStart -> calls.played += id to fromStart },
            onSelectSeason = {},
            onToggleFavorite = {},
            onToggleWatched = {},
            onToggleEpisodeWatched = { calls.toggled += it },
            onRetry = {},
            onBack = {},
        )
    }

    @Test
    fun okOnOpenResumesTheEpisodeInProgress() {
        val calls = SeriesCalls()
        showSeries(calls)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf(23L to false), calls.played)
    }

    @Test
    fun fromStartReplaysTheEpisodeInProgressFromZero() {
        val calls = SeriesCalls()
        showSeries(calls)
        // « Reprendre S2 · E3 » a le focus ; à droite, « Depuis le début ».
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf(23L to true), calls.played)
    }

    @Test
    fun aSeriesNotStartedHasNeitherProgressNorFromStart() {
        val calls = SeriesCalls()
        showSeries(calls, DetailFakes.seriesNotStarted)
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.play)).assertExists()
        compose.onNodeWithText(context.getString(R.string.from_start)).assertDoesNotExist()
        // Aucun « Vu jusqu’à … », quelle que soit la position.
        val watchedUntil = context.getString(R.string.watched_until, "").trim()
        compose.onNodeWithText(watchedUntil, substring = true).assertDoesNotExist()
    }

    @Test
    fun theEpisodeInProgressShowsHowFarItWasWatched() {
        showSeries(SeriesCalls())
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(context.getString(R.string.from_start)).assertExists()
        compose.onNodeWithText(context.getString(R.string.watched_until, "22:00")).assertExists()
    }

    @Test
    fun menuTogglesTheWatchedMarkOfAnEpisode() {
        val calls = SeriesCalls()
        showSeries(calls)
        compose.onNodeWithText("Le retour").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        assertEquals(listOf(21L), calls.toggled)
        assertEquals(emptyList<Pair<Long, Boolean>>(), calls.played)
    }

    @Test
    fun okOnAnEpisodePlaysItAndLongPressMarksIt() {
        val calls = SeriesCalls()
        showSeries(calls)
        compose.onNodeWithText("Le retour").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf(21L to false), calls.played)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter, pressDurationMillis = 1_500) }
        compose.waitForIdle()
        assertEquals(listOf(21L), calls.toggled)
        assertEquals(1, calls.played.size)
    }
}

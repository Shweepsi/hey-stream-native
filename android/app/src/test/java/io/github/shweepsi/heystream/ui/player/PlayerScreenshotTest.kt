package io.github.shweepsi.heystream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.capture
import io.github.shweepsi.heystream.ui.detail.InFrench
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Lecteur plein écran (`./gradlew recordRoborazziDebug`). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val noActions = object : PlayerActions {
        override fun togglePause() = Unit

        override fun seekBy(deltaMs: Long) = Unit

        override fun seekTo(fraction: Float) = Unit

        override fun selectTrack(key: TrackKey) = Unit

        override fun disableSubtitles() = Unit

        override fun retry() = Unit

        override fun playNext() = Unit

        override fun cancelNext() = Unit

        override fun exit() = Unit
    }

    private val tracks = TrackLists(
        audio = listOf(
            TrackOption(TrackKey(0, 0), language = "fr", label = null, channels = 6, number = 1, selected = true),
            TrackOption(TrackKey(1, 0), language = "en", label = null, channels = 2, number = 2, selected = false),
            TrackOption(TrackKey(2, 0), language = "fr", label = "Audiodescription", channels = 2, number = 3, selected = false),
        ),
        text = listOf(
            TrackOption(TrackKey(3, 0), language = "fr", label = "Sourds et malentendants", channels = 0, number = 1, selected = false),
            TrackOption(TrackKey(4, 0), language = null, label = null, channels = 0, number = 2, selected = false),
        ),
    )

    private val episodeState = PlayerUiState(
        title = "Les Veilleurs du large",
        episode = EpisodeLabel(season = 2, number = 3, title = "Le phare"),
        resolving = false,
        tracks = tracks,
    )

    private val playing = PlaybackStatus(active = true, playing = true)

    private fun player(
        name: String,
        device: Device,
        state: PlayerUiState = episodeState,
        status: PlaybackStatus = playing,
        chrome: PlayerChrome = PlayerChrome(visible = true),
    ) = compose.capture(name, device) {
        InFrench {
            PlayerContent(
                state = state,
                status = status,
                position = PlaybackPosition(positionMs = 1_324_000, durationMs = 2_640_000),
                chrome = chrome,
                actions = noActions,
                video = { Box(Modifier.fillMaxSize().background(Color(0xFF3B4450))) },
            )
        }
    }

    @Test fun controlsTv() = player("player_controls", Device.Tv)

    @Test fun controlsTablet() = player("player_controls", Device.Tablet)

    @Test fun controlsPhone() = player("player_controls", Device.Phone)

    @Test fun seekFocusTv() = player("player_seek", Device.Tv, chrome = PlayerChrome(visible = true, focus = ControlFocus.Seek))

    @Test fun subtitlesMenuTv() = player("player_subtitles_menu", Device.Tv, chrome = PlayerChrome(visible = true, menu = TrackMenu.Subtitles))

    @Test fun audioMenuTv() = player("player_audio_menu", Device.Tv, chrome = PlayerChrome(visible = true, menu = TrackMenu.Audio))

    @Test fun nextEpisodeTv() = player(
        "player_next_episode",
        Device.Tv,
        state = episodeState.copy(next = NextEpisodeUi(EpisodeLabel(2, 4, "Marée haute"), secondsLeft = 7)),
        status = PlaybackStatus(active = true, ended = true),
        chrome = PlayerChrome(visible = false),
    )

    @Test fun nextEpisodePhone() = player(
        "player_next_episode",
        Device.Phone,
        state = episodeState.copy(next = NextEpisodeUi(EpisodeLabel(2, 4, "Marée haute"), secondsLeft = 7)),
        status = PlaybackStatus(active = true, ended = true),
        chrome = PlayerChrome(visible = false),
    )

    @Test fun reconnectingTv() = player(
        "player_reconnecting",
        Device.Tv,
        status = PlaybackStatus(active = true, buffering = true, reconnectAttempt = 2),
        chrome = PlayerChrome(visible = false),
    )

    @Test fun failedTv() = player(
        "player_failed",
        Device.Tv,
        status = PlaybackStatus(active = true, failed = true),
        chrome = PlayerChrome(visible = false),
    )

    @Test fun unavailableTablet() = player(
        "player_unavailable",
        Device.Tablet,
        state = PlayerUiState(resolving = false, error = UiText(R.string.error_network)),
        status = PlaybackStatus(),
        chrome = PlayerChrome(visible = false),
    )
}

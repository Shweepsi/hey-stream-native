package io.github.shweepsi.heystream.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.capture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Fiches film et série (`./gradlew recordRoborazziDebug`). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun movie(name: String, device: Device, state: MovieDetailState, french: Boolean = true) = compose.capture(name, device) {
        val content = @Composable {
            MovieDetailContent(state, onPlay = {}, onToggleFavorite = {}, onToggleWatched = {}, onRetry = {}, onBack = {})
        }
        if (french) InFrench(content) else content()
    }

    private fun series(name: String, device: Device, state: SeriesDetailState) = compose.capture(name, device) {
        InFrench { SeriesContent(state) }
    }

    @Composable
    private fun SeriesContent(state: SeriesDetailState) {
        SeriesDetailContent(
            state = state,
            onPlayEpisode = { _, _ -> },
            onSelectSeason = {},
            onToggleFavorite = {},
            onToggleWatched = {},
            onToggleEpisodeWatched = {},
            onRetry = {},
            onBack = {},
        )
    }

    @Test fun movieStartedTv() = movie("detail_movie_started", Device.Tv, DetailFakes.startedMovie)

    @Test fun movieStartedTablet() = movie("detail_movie_started", Device.Tablet, DetailFakes.startedMovie)

    @Test fun movieStartedPhone() = movie("detail_movie_started", Device.Phone, DetailFakes.startedMovie)

    @Test fun movieNewTabletDark() = movie("detail_movie_new", Device.TabletDark, DetailFakes.newMovie)

    @Test fun movieNewPhone() = movie("detail_movie_new", Device.Phone, DetailFakes.newMovie)

    @Test fun movieStartedTvEnglish() = movie("detail_movie_started_en", Device.Tv, DetailFakes.startedMovie, french = false)

    @Test fun movieNewTv() = movie("detail_movie_new", Device.Tv, DetailFakes.newMovie)

    @Test fun movieErrorTv() = movie("detail_movie_error", Device.Tv, DetailFakes.movieError)

    @Test fun seriesTv() = series("detail_series", Device.Tv, DetailFakes.series)

    @Test fun seriesTablet() = series("detail_series", Device.Tablet, DetailFakes.series)

    @Test fun seriesPhone() = series("detail_series", Device.Phone, DetailFakes.series)

    @Test fun seriesNotStartedTv() = series("detail_series_new", Device.Tv, DetailFakes.seriesNotStarted)

    @Test fun seriesLoadingEpisodesTv() = series("detail_series_loading", Device.Tv, DetailFakes.seriesLoadingEpisodes)
}

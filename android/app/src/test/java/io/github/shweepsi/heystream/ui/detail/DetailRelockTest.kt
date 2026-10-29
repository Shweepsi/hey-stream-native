package io.github.shweepsi.heystream.ui.detail

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Episode
import io.github.shweepsi.heystream.core.MovieInfo
import io.github.shweepsi.heystream.core.Season
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SeriesInfo
import io.github.shweepsi.heystream.data.FakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.runMainUntil
import io.github.shweepsi.heystream.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fiche d'un titre adulte ouverte (catégories déverrouillées), puis l'app
 * quittée : le reverrouillage efface tout de suite ce qu'elle montrait et
 * affiche le verrouillage, même si l'écran n'est pas visible (sous le lecteur).
 */
@RunWith(AndroidJUnit4::class)
class DetailRelockTest {
    private val core = FakeCore().apply {
        add(Section.MOVIES, MOVIE, "Film adulte", adult = true)
        movieInfos[MOVIE] = MovieInfo(
            name = "Film adulte",
            plot = "Résumé",
            year = "2024",
            durationSecs = 5_400,
            rating = "6",
            image = null,
            extension = "mkv",
        )
        add(Section.SERIES, SERIES, "Série adulte", adult = true)
        seriesInfos[SERIES] = SeriesInfo(
            name = "Série adulte",
            plot = "Résumé",
            year = "2024",
            rating = "6",
            cover = null,
            seasons = listOf(
                Season(
                    number = 1,
                    name = null,
                    episodes = listOf(
                        Episode(id = 51, season = 1, number = 1, title = "Premier", extension = "mkv", plot = null, image = null, durationSecs = 2_640),
                    ),
                ),
            ),
        )
    }
    private val session = fakeSession(core)

    private val locked = UiText(R.string.error_parental_lock)

    @Test
    fun movieDetailForgetsTheFilmWhenRelocked() {
        val viewModel = MovieDetailViewModel(session, MOVIE)
        runMainUntil { viewModel.state.value.movie?.header?.plot != null }

        session.lockParental()
        runMainUntil { viewModel.state.value.error != null }
        assertEquals(locked, viewModel.state.value.error)
        assertNull(viewModel.state.value.movie)
    }

    @Test
    fun seriesDetailForgetsSeasonsAndEpisodesWhenRelocked() {
        val viewModel = SeriesDetailViewModel(session, SERIES)
        runMainUntil { viewModel.state.value.episodes.isNotEmpty() }

        session.lockParental()
        runMainUntil { viewModel.state.value.error != null }
        val state = viewModel.state.value
        assertEquals(locked, state.error)
        assertNull(state.header)
        assertTrue(state.seasons.isEmpty())
        assertTrue(state.episodes.isEmpty())
        assertNull(state.playTarget)
    }

    private companion object {
        const val MOVIE = 42L
        const val SERIES = 5L
    }
}

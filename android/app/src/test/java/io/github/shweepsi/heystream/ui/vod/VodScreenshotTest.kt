package io.github.shweepsi.heystream.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.capture
import io.github.shweepsi.heystream.ui.vod.VodFixtures.Paged
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieCategories
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieId
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movies
import io.github.shweepsi.heystream.ui.vod.VodFixtures.moviesState
import io.github.shweepsi.heystream.ui.vod.VodFixtures.seriesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Captures de l'écran Films / Séries (`recordRoborazziDebug`), avec des données inventées. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VodScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** Comme `capture`, en français sur TV : textes et pluriels français. */
    private fun captureFrenchTv(name: String, content: @Composable () -> Unit) {
        compose.setTvContent(locale = "fr", content = content)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${name}_tv_fr.png")
    }

    /** Affiche focalisée (retour d'une fiche), barres de progression, coches « vu ». */
    @Test
    fun moviesTv() = compose.capture("vod_movies", Device.Tv) { Paged(moviesState, movies(), focus = movieId(1)) }

    @Test
    fun moviesTvFrench() = captureFrenchTv("vod_movies") {
        Paged(moviesState.copy(categories = movieCategories(selectedCount = 1_284)), movies(), focus = movieId(4))
    }

    @Test
    fun moviesTablet() = compose.capture("vod_movies", Device.Tablet) { Paged(moviesState, movies()) }

    @Test
    fun moviesPhone() = compose.capture("vod_movies", Device.Phone) { Paged(moviesState, movies()) }

    /** Ouverture sur TV : le focus est sur la catégorie ouverte. */
    @Test
    fun seriesContinueTv() = compose.capture("vod_series_continue", Device.Tv) {
        VodContent(seriesState, null, {}, {}, {}, {}, {})
    }

    @Test
    fun seriesContinueTvFrench() = captureFrenchTv("vod_series_continue") {
        VodContent(seriesState, null, {}, {}, {}, {}, {})
    }

    @Test
    fun seriesContinuePhone() = compose.capture("vod_series_continue", Device.Phone) {
        VodContent(seriesState, null, {}, {}, {}, {}, {})
    }

    @Test
    fun continueEmptyTvFrench() = captureFrenchTv("vod_continue_empty") {
        val categories = listOf(VodCategory(CategoryKey.Continue, null, 0)) + movieCategories().drop(1)
        VodContent(moviesState.copy(categories = categories, selected = CategoryKey.Continue), null, {}, {}, {}, {}, {})
    }

    @Test
    fun favoritesEmptyTablet() = compose.capture("vod_favorites_empty", Device.Tablet) {
        val categories = seriesState.categories.map { if (it.key == CategoryKey.Favorites) it.copy(count = 0) else it }
        Paged(seriesState.copy(categories = categories, selected = CategoryKey.Favorites), emptyList())
    }

    /** Premier lancement : catalogue en cours de téléchargement, aucune catégorie. */
    @Test
    fun downloadingTv() = compose.capture("vod_downloading", Device.Tv) {
        VodContent(
            VodUiState(
                section = Section.MOVIES,
                loading = false,
                categories = movieCategories().take(2).map { it.copy(count = 0) },
                downloading = true,
            ),
            null, {}, {}, {}, {}, {},
        )
    }

    /** Catalogue pas encore téléchargé (téléchargement en échec) : « Réessayer » a le focus. */
    @Test
    fun catalogEmptyTv() = compose.capture("vod_catalog_empty", Device.Tv) {
        VodContent(
            VodUiState(
                section = Section.SERIES,
                loading = false,
                categories = seriesState.categories.take(2).map { it.copy(count = 0) },
            ),
            null, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun errorPhone() = compose.capture("vod_error", Device.Phone) {
        VodContent(
            VodUiState(section = Section.SERIES, loading = false, error = UiText(R.string.error_storage)),
            null, {}, {}, {}, {}, {},
        )
    }
}

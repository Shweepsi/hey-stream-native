package io.github.shweepsi.heystream.ui.vod

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.vod.VodFixtures.Paged
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieId
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieTitles
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movies
import io.github.shweepsi.heystream.ui.vod.VodFixtures.moviesState
import io.github.shweepsi.heystream.ui.vod.VodFixtures.seriesState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Télécommande : focus d'ouverture, retour d'une fiche, passages catégories ↔ grille. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class VodFocusTest {
    @get:Rule
    val compose = createComposeRule()

    /** Affiche activable (son libellé d'action est le titre). */
    private fun poster(index: Int): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher("affiche ${movieTitles[index]}") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == movieTitles[index]
        },
    )

    private fun category(name: String) = compose.onNode(hasText(name) and hasClickAction())

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun openingFocusesTheOpenCategory() {
        compose.setTvContent { Paged(moviesState, movies()) }
        category("Nouveautés").assertIsFocused()
    }

    @Test
    fun returningFromTheFicheFocusesTheOpenedPoster() {
        compose.setTvContent { Paged(moviesState, movies(), focus = movieId(4)) }
        poster(4).assertIsFocused()
        category("Nouveautés").assertIsNotFocused()
    }

    @Test
    fun posterNoLongerListedFallsBackToTheOpenCategory() {
        compose.setTvContent { Paged(moviesState, movies(), focus = 999L) }
        category("Nouveautés").assertIsFocused()
    }

    @Test
    fun emptiedContinueFallsBackToTheOpenCategory() {
        compose.setTvContent {
            VodContent(seriesState.copy(continueItems = emptyList()), null, {}, {}, {}, {}, {}, focusPosterId = 500L)
        }
        category("Continue").assertIsFocused()
    }

    @Test
    fun leftFromTheFirstColumnGoesToTheOpenCategoryAndRightComesBack() {
        compose.setTvContent { Paged(moviesState, movies(), focus = movieId(6)) }
        poster(6).assertIsFocused()
        press(Key.DirectionLeft)
        // La catégorie ouverte, pas la plus proche à l'écran.
        category("Nouveautés").assertIsFocused()
        press(Key.DirectionRight)
        poster(6).assertIsFocused()
    }

    @Test
    fun rightFromTheCategoriesEntersTheGrid() {
        compose.setTvContent { Paged(moviesState, movies()) }
        press(Key.DirectionRight)
        category("Nouveautés").assertIsNotFocused()
        assertEquals(true, (0 until 6).any { index -> runCatching { poster(index).assertIsFocused() }.isSuccess })
    }

    @Test
    fun upFromTheFirstRowStaysInTheGrid() {
        compose.setTvContent { Paged(moviesState, movies(), focus = movieId(1)) }
        press(Key.DirectionUp)
        poster(1).assertIsFocused()
        press(Key.DirectionDown)
        poster(7).assertIsFocused()
    }

    @Test
    fun errorFocusesRetry() {
        var retried = false
        compose.setTvContent {
            VodContent(
                VodUiState(section = Section.MOVIES, loading = false, error = UiText(R.string.error_storage)),
                null, {}, {}, { retried = true }, {}, {},
            )
        }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(true, retried)
    }

    @Test
    fun okOnAPosterOpensItsFiche() {
        var opened: Long? = null
        compose.setTvContent { Paged(moviesState, movies(), focus = movieId(3), onOpenItem = { opened = it }) }
        press(Key.DirectionCenter)
        assertEquals(movieId(3), opened)
    }

    @Test
    fun okOnACategoryOpensIt() {
        var clicked: VodCategory? = null
        compose.setTvContent { Paged(moviesState, movies(), onCategoryClick = { clicked = it }) }
        press(Key.DirectionDown)
        category("Action").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(CategoryKey.Provider("2"), clicked?.key)
    }
}

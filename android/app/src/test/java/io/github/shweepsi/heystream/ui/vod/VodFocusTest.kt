package io.github.shweepsi.heystream.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
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
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.CatalogPaging
import io.github.shweepsi.heystream.data.OffsetPagingSource
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.vod.VodFixtures.Paged
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieCategories
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieId
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movieTitles
import io.github.shweepsi.heystream.ui.vod.VodFixtures.movies
import io.github.shweepsi.heystream.ui.vod.VodFixtures.moviesState
import io.github.shweepsi.heystream.ui.vod.VodFixtures.seriesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
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

    private fun press(key: Key, times: Int = 1) {
        repeat(times) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    /** Affiche activable de libellé [title]. */
    private fun posterTitled(title: String): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher("affiche $title") { it.config.getOrNull(SemanticsActions.OnClick)?.label == title },
    )

    /** Titres inventés d'une longue catégorie. */
    private val longList = (0 until 300).map { PosterUi(1_000L + it, "Film $it", null, null, false) }

    /** Grille paginée par le vrai `Pager`, sur [load]. */
    private fun pager(scope: CoroutineScope, load: (offset: Int, limit: Int) -> List<PosterUi>) =
        Pager(CatalogPaging.config) { OffsetPagingSource(total = { longList.size }, load = { offset, limit -> load(offset, limit) }) }
            .flow
            .cachedIn(scope)

    private fun page(offset: Int, limit: Int) =
        longList.subList(offset.coerceIn(0, longList.size), (offset + limit).coerceIn(0, longList.size))

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

    @Test
    fun returningFromTheFicheRestoresAPosterFarDownAPagedCategory() {
        var shown by mutableStateOf(true)
        compose.setTvContent {
            val scope = rememberCoroutineScope()
            val flow = remember { pager(scope, ::page) }
            // Comme la navigation : l'écran quitte la composition, son état est sauvegardé.
            val holder = rememberSaveableStateHolder()
            if (shown) {
                holder.SaveableStateProvider("vod") {
                    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }
                    VodContent(
                        moviesState, flow.collectAsLazyPagingItems(), {},
                        onOpenItem = { id ->
                            lastOpened = id
                            shown = false
                        },
                        {}, {}, {},
                        focusPosterId = lastOpened,
                    )
                }
            }
        }
        press(Key.DirectionRight)
        press(Key.DirectionDown, 20)
        press(Key.DirectionRight, 2)
        posterTitled("Film 122").assertIsFocused()
        press(Key.DirectionCenter)
        shown = true
        compose.waitForIdle()
        posterTitled("Film 122").assertIsFocused()
    }

    @Test
    fun catalogRefreshedWhileScrollingDoesNotCrashTheGrid() {
        compose.setTvContent {
            val scope = rememberCoroutineScope()
            // Catalogue rafraîchi entre deux pages : tout est décalé d'un rang,
            // le dernier titre de la première page revient en tête de la suivante.
            val flow = remember { pager(scope) { offset, limit -> page(if (offset == 0) 0 else offset - 1, limit) } }
            VodContent(moviesState, flow.collectAsLazyPagingItems(), {}, {}, {}, {}, {})
        }
        press(Key.DirectionRight)
        press(Key.DirectionDown, 14)
        posterTitled("Film 83").assertIsFocused()
    }

    @Test
    fun aFocusedPosterThatLeavesTheListHandsFocusToTheOpenCategory() {
        var posters by mutableStateOf(movies())
        compose.setTvContent {
            val items = remember(posters) { flowOf(PagingData.from(posters)) }.collectAsLazyPagingItems()
            VodContent(moviesState, items, {}, {}, {}, {}, {}, focusPosterId = movieId(8))
        }
        poster(8).assertIsFocused()
        // Par exemple : retiré des favoris depuis sa fiche, liste relue au retour.
        posters = posters.filter { it.id != movieId(8) }
        compose.waitForIdle()
        category("Nouveautés").assertIsFocused()
    }

    @Test
    fun openCategoryOutsideTheVisibleListStillGetsFocus() {
        val many = movieCategories() + (12 until 40).map { VodCategory(CategoryKey.Provider("$it"), "Genre $it", 10) }
        compose.setTvContent {
            Paged(moviesState.copy(categories = many, selected = CategoryKey.Provider("38")), movies())
        }
        category("Genre 38").assertIsFocused()
    }

    @Test
    fun emptyCatalogFocusesRetry() {
        var refreshed = false
        compose.setTvContent {
            VodContent(
                VodUiState(
                    section = Section.MOVIES,
                    loading = false,
                    categories = movieCategories().take(2).map { it.copy(count = 0) },
                ),
                null, {}, {}, {}, { refreshed = true }, {},
            )
        }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(true, refreshed)
    }
}

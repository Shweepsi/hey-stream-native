package io.github.shweepsi.heystream.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import io.github.shweepsi.heystream.ui.theme.Hs
import kotlinx.coroutines.flow.flowOf
import org.robolectric.RuntimeEnvironment

/** Données inventées pour les tests de l'écran Films / Séries (aucune image, aucun réseau). */
object VodFixtures {
    val movieTitles = listOf(
        "La Dernière Marée", "Nord Express", "Les Jardiniers", "Point de rupture", "Un été à Sète", "L’Horloger",
        "Orbite basse", "Les Cinq Saisons", "Brume", "Le Phare", "Mécanique céleste", "Vingt et un",
        "Le Grand Large", "Minuit passé", "Les Passagers", "Ligne de crête", "Sous la glace", "Contre-courant",
    )

    private val seriesTitles = listOf("Les Veilleurs", "Station Polaire", "Brigade du port", "La Maison d’en face", "Hautes Terres")

    /** Identifiant de la n-ième affiche de [movies]. */
    fun movieId(index: Int) = 100L + index

    fun movies(): List<PosterUi> = movieTitles.mapIndexed { index, title ->
        PosterUi(
            id = movieId(index),
            title = title,
            image = null,
            progress = when (index) {
                4 -> 0.6f
                9 -> 0.25f
                // Revu : coche et barre.
                13 -> 0.4f
                else -> null
            },
            watched = index == 2 || index == 7 || index == 13,
        )
    }

    fun movieCategories(selectedCount: Long = 214) = listOf(
        VodCategory(CategoryKey.Continue, null, 3),
        VodCategory(CategoryKey.Favorites, null, 12),
        VodCategory(CategoryKey.Provider("1"), "Nouveautés", selectedCount),
        VodCategory(CategoryKey.Provider("2"), "Action", 1_284),
        VodCategory(CategoryKey.Provider("3"), "Comédie", 976),
        VodCategory(CategoryKey.Provider("4"), "Drame", 812),
        VodCategory(CategoryKey.Provider("5"), "Animation", 341),
        VodCategory(CategoryKey.Provider("6"), "Documentaire", 129),
        VodCategory(CategoryKey.Provider("7"), "Science-fiction", 208),
        VodCategory(CategoryKey.Provider("8"), "Adultes", null, locked = true),
        VodCategory(CategoryKey.Provider("9"), "Policier", 402),
        VodCategory(CategoryKey.Provider("10"), "Famille", 233),
        VodCategory(CategoryKey.Provider("11"), "Classiques", 517),
    )

    /** Films, catégorie « Nouveautés » ouverte. */
    val moviesState = VodUiState(
        section = Section.MOVIES,
        loading = false,
        categories = movieCategories(),
        selected = CategoryKey.Provider("1"),
    )

    val seriesContinue = seriesTitles.mapIndexed { index, title ->
        PosterUi(id = 500L + index, title = title, image = null, progress = listOf(0.8f, 0.15f, 0.5f, 0.95f, 0.05f)[index], watched = false)
    }

    /** Séries, « Continuer » ouvert. */
    val seriesState = VodUiState(
        section = Section.SERIES,
        loading = false,
        categories = listOf(
            VodCategory(CategoryKey.Continue, null, seriesContinue.size.toLong()),
            VodCategory(CategoryKey.Favorites, null, 4),
            VodCategory(CategoryKey.Provider("20"), "Séries françaises", 38),
            VodCategory(CategoryKey.Provider("21"), "Policières", 112),
            VodCategory(CategoryKey.Provider("22"), "Animation", 64),
            VodCategory(CategoryKey.Provider("23"), "Documentaires", 27),
        ),
        selected = CategoryKey.Continue,
        continueItems = seriesContinue,
    )

    /** Liste lue jusqu'au bout (une catégorie vide affiche son explication). */
    private val Loaded = LoadStates(LoadState.NotLoading(false), LoadState.NotLoading(true), LoadState.NotLoading(true))

    /** L'écran avec une grille paginée de [posters]. */
    @Composable
    fun Paged(
        state: VodUiState,
        posters: List<PosterUi>,
        focus: Long? = null,
        onCategoryClick: (VodCategory) -> Unit = {},
        onOpenItem: (Long) -> Unit = {},
    ) {
        val items = remember { flowOf(PagingData.from(posters, Loaded)) }.collectAsLazyPagingItems()
        VodContent(state, items, onCategoryClick, onOpenItem, {}, {}, {}, focusPosterId = focus)
    }
}

/** Télécommande : Robolectric reste sinon en mode tactile, où rien ne prend le focus. */
private val KeyboardInput = object : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/** Compose [content] comme sur TV (thème, télécommande), en [locale] si donnée. */
fun ComposeContentTestRule.setTvContent(locale: String? = null, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers(if (locale == null) Device.Tv.qualifiers else "$locale-${Device.Tv.qualifiers}")
    setContent {
        CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
            HeyStreamTheme(isTv = true) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Hs.colors.background),
                ) { content() }
            }
        }
    }
    waitForIdle()
}

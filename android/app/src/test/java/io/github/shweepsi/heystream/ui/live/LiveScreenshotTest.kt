package io.github.shweepsi.heystream.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.capture
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/** Captures du direct, avec de fausses données (aucun cœur natif, aucun réseau). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** 21:55, heure locale de la machine de test : les heures affichées restent les mêmes. */
    private val now: Long = LocalDateTime.of(2026, 10, 28, 21, 55).atZone(ZoneId.systemDefault()).toEpochSecond()

    private fun channel(id: Long, number: Long?, name: String) = CatalogItem(
        section = Section.LIVE,
        id = id,
        name = name,
        image = null,
        categoryId = "news",
        extension = null,
        epgChannelId = null,
        rating = null,
        plot = null,
        number = number,
        progress = null,
        watched = false,
    )

    private val channels = listOf(
        channel(1, 101, "Info Monde"),
        channel(2, 102, "Info Continu"),
        channel(3, 103, "Éco Direct"),
        channel(4, 104, "Info Régions"),
        channel(5, 105, "Parlement TV"),
        channel(6, 106, "Info Sport"),
        channel(7, 107, "Monde 24"),
        channel(8, 108, "Info Tech"),
        channel(9, 109, "Info Culture"),
        channel(10, 1024, "Météo Plus Régions et Montagne"),
        channel(11, 1025, "Radio Info"),
        channel(12, null, "Canal Local"),
    )

    /** Programme en cours déjà en cache pour certaines chaînes seulement. */
    private val nowTitles = mapOf(
        1L to "Le journal du soir",
        2L to "Débat : l’actualité de la semaine",
        3L to "Les marchés en clôture",
        5L to "Questions au gouvernement",
        6L to "Le résumé de la journée",
        7L to "Reportage : les océans",
        8L to "Le magazine du numérique",
        10L to "Prévisions pour demain",
    )

    private fun categories(): CategoriesUi.Ready {
        val entries = listOf(
            "news" to "Information",
            "general" to "Généralistes",
            "sport" to "Sport",
            "cinema" to "Cinéma",
            "kids" to "Jeunesse",
            "docs" to "Documentaires",
            "music" to "Musique",
            "adult" to "Adultes",
            "local" to "Régions",
            "world" to "International",
            "radio" to "Radios",
        ).map { (id, name) -> CategoryEntry(id, name, isAdult = id == "adult", locked = id == "adult", hidden = false) }
        val counts = mapOf("news" to 38L, "general" to 64L, "sport" to 120L, "cinema" to 92L, "kids" to 41L, "docs" to 57L, "music" to 33L, "local" to 24L, "world" to 210L, "radio" to 18L)
        return CategoriesUi.Ready(liveCategories(entries, counts, favorites = 12, recents = 5))
    }

    private val preview = LivePreview(
        id = 1,
        name = "Info Monde",
        number = 101,
        now = LiveProgramme("Le journal du soir", start = now - 25 * 60, stop = now + 45 * 60),
        next = LiveProgramme("Grand entretien", start = now + 45 * 60, stop = now + 95 * 60),
    )

    private val playing = LiveUiState(
        categories = categories(),
        selected = CategoryKey.Provider("news"),
        favoriteIds = setOf(1L, 4L),
        nowTitles = nowTitles,
        preview = preview,
        playback = LivePlayback.Playing,
    )

    private fun live(name: String, device: Device, state: LiveUiState, items: List<CatalogItem> = channels) =
        compose.capture(name, device) { Live(state, items) }

    @Composable
    private fun Live(state: LiveUiState, items: List<CatalogItem>) {
        // Liste chargée en entier : états « fin de liste » (sinon la liste vide resterait « en cours »).
        val flow = remember { flowOf(PagingData.from(items, sourceLoadStates = Loaded)) }
        LiveContent(
            state = state,
            channels = flow.collectAsLazyPagingItems(),
            nowSecs = now,
            actions = object : LiveActions {},
            onBack = {},
            video = { modifier -> Box(modifier.background(FakePicture)) },
        )
    }

    @Test fun liveTv() = live("live", Device.Tv, playing)

    /** Textes français (typographie) : même écran, ressources `values-fr`. */
    @Test
    fun liveTvFrench() {
        RuntimeEnvironment.setQualifiers("fr-rFR-" + Device.Tv.qualifiers)
        compose.setContent {
            HeyStreamTheme(isTv = true, systemDark = true) {
                CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                    Live(playing.copy(message = UiText(R.string.live_favorite_removed, listOf("Info Monde"))), channels)
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/live_fr_tv.png")
    }

    @Test fun liveTvFullscreen() = live("live_fullscreen", Device.Tv, playing.copy(fullscreen = true, overlayVisible = true))

    @Test fun liveTablet() = live("live", Device.Tablet, playing.copy(message = UiText(R.string.live_favorite_added, listOf("Info Régions"))))

    @Test fun liveTabletDark() = live("live", Device.TabletDark, playing.copy(playback = LivePlayback.Reconnecting))

    @Test fun livePhone() = live("live", Device.Phone, playing)

    @Test fun liveTabletFullscreen() = live("live_fullscreen", Device.Tablet, playing.copy(fullscreen = true, overlayVisible = true))

    @Test
    fun liveTvFailed() = live(
        "live_failed",
        Device.Tv,
        playing.copy(playback = LivePlayback.Failed(), preview = preview.copy(now = null, next = null)),
    )

    @Test
    fun liveTvEmptyFavorites() = live(
        "live_favorites_empty",
        Device.Tv,
        LiveUiState(categories = categories(), selected = CategoryKey.Favorites),
        items = emptyList(),
    )

    @Test
    fun liveTvDownloading() = live(
        "live_downloading",
        Device.Tv,
        LiveUiState(categories = CategoriesUi.Ready(liveCategories(emptyList(), emptyMap(), 0, 0)), downloading = true),
        items = emptyList(),
    )

    private companion object {
        /** Télécommande (comme `capture`) : le focus de départ est visible. */
        val KeyboardInput = object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }

        /** Image de la vidéo, en aplat (pas de vrai lecteur dans les tests). */
        val FakePicture = Color(0xFF2B3542)

        val Loaded = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = true),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )
    }
}

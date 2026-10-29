package io.github.shweepsi.heystream.ui.search

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.capture
import io.github.shweepsi.heystream.ui.settings.captureFrench
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val channels = listOf(
        hit(Section.LIVE, 1, "Info Monde", number = 101),
        hit(Section.LIVE, 2, "Info Monde HD", number = 102),
        hit(Section.LIVE, 3, "Monde 24"),
        hit(Section.LIVE, 4, "Le Monde des animaux", number = 340),
    )
    private val movies = listOf(
        hit(Section.MOVIES, 11, "Le Monde perdu", progress = 0.4f),
        hit(Section.MOVIES, 12, "Un monde parfait", watched = true),
        hit(Section.MOVIES, 13, "Le Nouveau Monde"),
        hit(Section.MOVIES, 14, "Mondes parallèles"),
        hit(Section.MOVIES, 15, "Le Tour du monde en 80 jours", progress = 0.8f),
        hit(Section.MOVIES, 16, "Monde sauvage"),
        hit(Section.MOVIES, 17, "La Fin du monde"),
        hit(Section.MOVIES, 18, "Monde à part"),
    )
    private val series = listOf(
        hit(Section.SERIES, 21, "Le Monde de demain", watched = true),
        hit(Section.SERIES, 22, "Mondes inconnus", progress = 0.15f),
        hit(Section.SERIES, 23, "Le Monde selon Garp"),
    )
    private val found = SearchResults.Found("monde", channels, movies, series)

    private fun results(device: Device, focusKey: String? = "MOVIES:12") = compose.capture("search", device) {
        SearchContent(query = "monde", results = found, onQueryChange = {}, onOpen = {}, onBack = {}, focusKey = focusKey)
    }

    @Test fun searchTv() = results(Device.Tv)

    @Test fun searchTablet() = results(Device.Tablet, focusKey = null)

    @Test fun searchPhone() = results(Device.Phone, focusKey = null)

    @Test fun searchTvFrench() = compose.captureFrench("search", Device.Tv) {
        SearchContent(query = "monde", results = found, onQueryChange = {}, onOpen = {}, onBack = {}, focusKey = "LIVE:2")
    }

    @Test fun searchEmptyTabletFrench() = compose.captureFrench("search_empty", Device.Tablet) {
        SearchContent(
            query = "zzz",
            results = SearchResults.Found("zzz", emptyList(), emptyList(), emptyList()),
            onQueryChange = {},
            onOpen = {},
            onBack = {},
        )
    }

    @Test fun searchIdlePhoneFrench() = compose.captureFrench("search_idle", Device.Phone) {
        SearchContent(query = "", results = SearchResults.Idle, onQueryChange = {}, onOpen = {}, onBack = {})
    }

    @Test fun searchEmptyTv() = compose.capture("search_empty", Device.Tv) {
        SearchContent(
            query = "zzz",
            results = SearchResults.Found("zzz", emptyList(), emptyList(), emptyList()),
            onQueryChange = {},
            onOpen = {},
            onBack = {},
        )
    }

    @Test fun searchEmptyPhone() = compose.capture("search_empty", Device.Phone) {
        SearchContent(
            query = "zzz",
            results = SearchResults.Found("zzz", emptyList(), emptyList(), emptyList()),
            onQueryChange = {},
            onOpen = {},
            onBack = {},
        )
    }

    @Test fun searchIdleTablet() = compose.capture("search_idle", Device.Tablet) {
        SearchContent(query = "", results = SearchResults.Idle, onQueryChange = {}, onOpen = {}, onBack = {})
    }

    private fun hit(section: Section, id: Long, name: String, number: Long? = null, progress: Float? = null, watched: Boolean = false) =
        SearchHit(section = section, id = id, name = name, image = null, number = number, progress = progress, watched = watched)
}

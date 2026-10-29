package io.github.shweepsi.heystream.ui.live

import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLogicTest {
    private fun entry(id: String, name: String, locked: Boolean = false) =
        CategoryEntry(id = id, name = name, isAdult = locked, locked = locked, hidden = false)

    private val entries = listOf(entry("1", "Information"), entry("9", "Adultes", locked = true), entry("2", "Sport"))

    @Test
    fun specialCategoriesComeFirstAndLockedOnesHaveNoCount() {
        val categories = liveCategories(entries, counts = mapOf("1" to 38L, "2" to 120L, "9" to 7L), favorites = 3, recents = 0)
        assertEquals(
            listOf(CategoryKey.Favorites, CategoryKey.Recents, CategoryKey.Provider("1"), CategoryKey.Provider("9"), CategoryKey.Provider("2")),
            categories.map { it.key },
        )
        assertEquals(3L, categories[0].count)
        assertEquals(0L, categories[1].count)
        assertEquals(38L, categories[2].count)
        assertTrue(categories[3].locked)
        assertNull(categories[3].count)
        assertNull(categories[0].name)
        assertEquals("Sport", categories[4].name)
    }

    @Test
    fun opensFavoritesWhenThereAreSome() {
        val categories = liveCategories(entries, emptyMap(), favorites = 2, recents = 4)
        assertEquals(CategoryKey.Favorites, defaultCategory(categories))
    }

    @Test
    fun otherwiseOpensTheFirstUnlockedProviderCategory() {
        val categories = liveCategories(listOf(entry("9", "Adultes", locked = true), entry("2", "Sport")), emptyMap(), 0, 4)
        assertEquals(CategoryKey.Provider("2"), defaultCategory(categories))
    }

    @Test
    fun channelFromSearchOpensItsCategoryUnlessLocked() {
        val categories = liveCategories(entries, emptyMap(), favorites = 2, recents = 0)
        assertEquals(CategoryKey.Provider("2"), defaultCategory(categories, preferred = "2"))
        assertEquals(CategoryKey.Favorites, defaultCategory(categories, preferred = "9"))
        assertEquals(CategoryKey.Favorites, defaultCategory(categories, preferred = "unknown"))
    }

    @Test
    fun emptyCatalogFallsBackToRecentsThenFavorites() {
        assertEquals(CategoryKey.Recents, defaultCategory(liveCategories(emptyList(), emptyMap(), 0, 3)))
        assertEquals(CategoryKey.Favorites, defaultCategory(liveCategories(emptyList(), emptyMap(), 0, 0)))
        assertTrue(CategoriesUi.Ready(liveCategories(emptyList(), emptyMap(), 0, 0)).catalogEmpty)
        assertFalse(CategoriesUi.Ready(liveCategories(entries, emptyMap(), 0, 0)).catalogEmpty)
        // Toutes les catégories masquées : Favoris et Récents restent utilisables.
        assertFalse(CategoriesUi.Ready(liveCategories(emptyList(), emptyMap(), 2, 0)).catalogEmpty)
    }

    @Test
    fun keepsTheOpenCategoryUnlessItDisappearsOrGetsLocked() {
        val categories = liveCategories(entries, emptyMap(), favorites = 0, recents = 0)
        assertEquals(CategoryKey.Provider("2"), keepCategory(CategoryKey.Provider("2"), categories))
        assertEquals(CategoryKey.Recents, keepCategory(CategoryKey.Recents, categories))
        assertEquals(CategoryKey.Provider("1"), keepCategory(CategoryKey.Provider("hidden"), categories))
        assertEquals(CategoryKey.Provider("1"), keepCategory(CategoryKey.Provider("9"), categories))
        assertEquals(CategoryKey.Provider("1"), keepCategory(null, categories))
    }

    @Test
    fun okOnTheChannelInPreviewGoesFullscreen() {
        assertEquals(ChannelClick.Preview, channelClick(previewId = null, clickedId = 5))
        assertEquals(ChannelClick.Preview, channelClick(previewId = 4, clickedId = 5))
        assertEquals(ChannelClick.Fullscreen, channelClick(previewId = 5, clickedId = 5))
    }

    @Test
    fun playbackStatusShownOverThePreview() {
        val playing = PlaybackStatus(active = true, live = true, playing = true)
        assertEquals(LivePlayback.Idle, livePlaybackOf(playing, hasPreview = false, previewError = null))
        assertEquals(LivePlayback.Playing, livePlaybackOf(playing, hasPreview = true, previewError = null))
        assertEquals(
            LivePlayback.Reconnecting,
            livePlaybackOf(playing.copy(reconnectAttempt = 2), hasPreview = true, previewError = null),
        )
        assertEquals(LivePlayback.Failed(), livePlaybackOf(playing.copy(failed = true), hasPreview = true, previewError = null))
        val error = UiText(R.string.error_network)
        assertEquals(LivePlayback.Failed(error), livePlaybackOf(PlaybackStatus(), hasPreview = true, previewError = error))
        assertEquals(LivePlayback.Idle, livePlaybackOf(PlaybackStatus(), hasPreview = true, previewError = null))
    }

    @Test
    fun programmeIsReloadedOnceItIsOver() {
        val preview = LivePreview(
            id = 1,
            name = "Info Monde",
            now = LiveProgramme("Le journal du soir", start = 1_000, stop = 2_000),
            next = LiveProgramme("Grand entretien", start = 2_000, stop = 3_000),
        )
        assertFalse(epgOutdated(preview, nowSecs = 1_500))
        assertTrue(epgOutdated(preview, nowSecs = 2_000))
        // Rien de connu : pas de nouvelle demande chaque minute.
        assertFalse(epgOutdated(preview.copy(now = null, next = null), nowSecs = 9_000))
        // Trou dans la grille : le suivant a commencé.
        assertTrue(epgOutdated(preview.copy(now = null), nowSecs = 2_100))
    }

    @Test
    fun visibleWindowAddsAMarginWithinBounds() {
        assertEquals(0..9, visibleWindow(firstVisible = 2, lastVisible = 5, itemCount = 100))
        assertEquals(46..58, visibleWindow(firstVisible = 50, lastVisible = 54, itemCount = 59))
        assertTrue(visibleWindow(0, 5, itemCount = 0).isEmpty())
        assertTrue(visibleWindow(5, 2, itemCount = 10).isEmpty())
    }

    @Test
    fun nowTitlesReplaceQueriedChannelsAndStaySmall() {
        val previous = mapOf(1L to "Ancien", 2L to "Journal", 3L to "Météo")
        val merged = mergeNowTitles(previous, queried = listOf(2L, 3L), found = mapOf(2L to "Débat"))
        // 3 n'a plus rien en cache : sa deuxième ligne disparaît ; 1 n'a pas été relue.
        assertEquals(mapOf(1L to "Ancien", 2L to "Débat"), merged)
        val full = (1L..300L).associateWith { "P$it" }
        assertEquals(mapOf(400L to "Neuf"), mergeNowTitles(full, listOf(400L), mapOf(400L to "Neuf"), max = 300))
    }

    @Test
    fun tvLayoutFollowsTheMockup() {
        val layout = liveLayout(isTv = true, width = 960.dp, height = 540.dp, categories = 180.dp, channels = 285.dp)
        assertEquals(531.dp, layout.videoX)
        assertEquals(24.dp, layout.videoY)
        assertEquals(399.dp, layout.videoWidth)
        assertEquals(399f * 9 / 16, layout.videoHeight.value, 0.01f)
        assertFalse(layout.compact)
    }

    @Test
    fun touchLayoutKeepsThreePanelsAndRoomForTheProgramme() {
        val tablet = liveLayout(isTv = false, width = 1280.dp, height = 800.dp, categories = 220.dp, channels = 360.dp)
        assertEquals(220.dp, tablet.categoriesWidth)
        assertEquals(360.dp, tablet.channelsWidth)
        assertEquals(604.dp, tablet.videoX)
        assertEquals(24.dp, tablet.videoY)
        assertEquals(652.dp, tablet.videoWidth)
        assertFalse(tablet.compact)

        val phone = liveLayout(isTv = false, width = 800.dp, height = 360.dp, categories = 220.dp, channels = 360.dp)
        assertTrue(phone.compact)
        assertTrue(phone.categoriesWidth < 220.dp && phone.channelsWidth < 360.dp)
        val below = 360.dp - phone.videoY
        assertTrue(phone.videoHeight <= below * 0.7f + 0.01.dp)
        assertEquals(phone.videoWidth.value * 9 / 16, phone.videoHeight.value, 0.01f)
    }

    @Test
    fun emptyFavoritesAndRecentsAreHiddenUnlessOpen() {
        val categories = liveCategories(listOf(CategoryEntry(id = "1", name = "Infos", isAdult = false, locked = false, hidden = false)), mapOf("1" to 3L), favorites = 0, recents = 2)
        assertEquals(
            listOf(CategoryKey.Recents, CategoryKey.Provider("1")),
            shownCategories(categories, CategoryKey.Provider("1")).map { it.key },
        )
        // Favoris ouvert (par défaut) : il reste, même vide.
        assertEquals(
            listOf(CategoryKey.Favorites, CategoryKey.Recents, CategoryKey.Provider("1")),
            shownCategories(categories, CategoryKey.Favorites).map { it.key },
        )
    }
}

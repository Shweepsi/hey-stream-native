package io.github.shweepsi.heystream.ui.vod

import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Progress
import io.github.shweepsi.heystream.core.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodLogicTest {
    private fun item(id: Long, progress: Progress? = null, watched: Boolean = false) = CatalogItem(
        section = Section.MOVIES,
        id = id,
        name = "Titre $id",
        image = null,
        categoryId = "1",
        extension = "mkv",
        epgChannelId = null,
        rating = null,
        plot = null,
        number = null,
        progress = progress,
        watched = watched,
    )

    private fun provider(id: String, locked: Boolean = false) =
        VodCategory(CategoryKey.Provider(id), name = "Catégorie $id", count = if (locked) null else 10, locked = locked)

    private fun poster(id: Long) = PosterUi(id, "Titre $id", null, 0.5f, watched = false)

    private fun data(
        categories: List<VodCategory> = listOf(provider("a"), provider("b")),
        continueItems: List<PosterUi> = emptyList(),
    ) = VodData(categories, continueItems, favoritesCount = 0)

    @Test
    fun startedTitleShowsItsProgress() {
        val poster = posterOf(item(1, Progress(positionMs = 30 * 60_000, durationMs = 120 * 60_000)))
        assertEquals(0.25f, poster.progress!!, 0.0001f)
        assertFalse(poster.watched)
    }

    @Test
    fun watchedTitleHasNoBarUnlessRewatched() {
        val watched = posterOf(item(1, watched = true))
        assertTrue(watched.watched)
        assertNull(watched.progress)

        val rewatching = posterOf(item(2, Progress(60_000, 600_000), watched = true))
        assertTrue(rewatching.watched)
        assertEquals(0.1f, rewatching.progress!!, 0.0001f)
    }

    @Test
    fun unknownDurationShowsNoBar() {
        assertNull(progressFraction(Progress(positionMs = 5_000, durationMs = 0)))
        assertNull(progressFraction(null))
        assertEquals(1f, progressFraction(Progress(positionMs = 9_000, durationMs = 5_000))!!, 0f)
    }

    @Test
    fun continueFallsBackOnTheResumePosition() {
        val poster = posterOf(item(1), fallback = Progress(positionMs = 50, durationMs = 100))
        assertEquals(0.5f, poster.progress!!, 0.0001f)
        // La progression connue du catalogue l'emporte.
        val known = posterOf(item(2, Progress(10, 100)), fallback = Progress(50, 100))
        assertEquals(0.1f, known.progress!!, 0.0001f)
    }

    @Test
    fun opensContinueByDefaultWhenItHasEntries() {
        assertEquals(CategoryKey.Continue, resolveSelection(null, null, data(continueItems = listOf(poster(1)))))
    }

    @Test
    fun opensTheFirstOpenableProviderCategoryOtherwise() {
        assertEquals(CategoryKey.Provider("a"), resolveSelection(null, null, data()))
        val lockedFirst = data(categories = listOf(provider("x", locked = true), provider("b")))
        assertEquals(CategoryKey.Provider("b"), resolveSelection(null, null, lockedFirst))
    }

    @Test
    fun opensNothingBeforeTheCatalogIsDownloaded() {
        assertNull(resolveSelection(null, null, data(categories = emptyList())))
        // Seulement des catégories verrouillées : « Continuer » et son explication.
        val onlyLocked = data(categories = listOf(provider("x", locked = true)))
        assertEquals(CategoryKey.Continue, resolveSelection(null, null, onlyLocked))
    }

    @Test
    fun keepsTheOpenCategoryWhenContinueEmpties() {
        // « Continuer » choisi par défaut, puis vidé (titre vu) : il reste ouvert.
        assertEquals(CategoryKey.Continue, resolveSelection(null, CategoryKey.Continue, data()))
    }

    @Test
    fun userChoiceWinsWhileItIsOpenable() {
        val choice = CategoryKey.Provider("b")
        assertEquals(choice, resolveSelection(choice, CategoryKey.Continue, data(continueItems = listOf(poster(1)))))
        assertEquals(CategoryKey.Favorites, resolveSelection(CategoryKey.Favorites, null, data()))
    }

    @Test
    fun lockedChoiceKeepsThePreviousCategoryUntilUnlocked() {
        val adult = CategoryKey.Provider("x")
        val locked = data(categories = listOf(provider("a"), provider("x", locked = true)))
        assertEquals(CategoryKey.Provider("a"), resolveSelection(adult, CategoryKey.Provider("a"), locked))
        val unlocked = data(categories = listOf(provider("a"), provider("x")))
        assertEquals(adult, resolveSelection(adult, CategoryKey.Provider("a"), unlocked))
    }

    @Test
    fun hiddenCategoryFallsBackToTheDefault() {
        val gone = CategoryKey.Provider("gone")
        assertEquals(CategoryKey.Provider("a"), resolveSelection(gone, gone, data()))
    }

    @Test
    fun specialCategoriesComeFirst() {
        val list = categoriesOf(VodData(listOf(provider("a")), listOf(poster(1), poster(2)), favoritesCount = 7))
        assertEquals(listOf(CategoryKey.Continue, CategoryKey.Favorites, CategoryKey.Provider("a")), list.map { it.key })
        assertEquals(listOf(2L, 7L, 10L), list.map { it.count })
    }

    @Test
    fun listKeysAreDistinct() {
        val keys = listOf(CategoryKey.Continue, CategoryKey.Favorites, CategoryKey.Provider("@continue")).map { it.listKey }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun playbackKindFollowsTheSection() {
        assertEquals(PlaybackKind.MOVIE, playbackKindOf(Section.MOVIES))
        assertEquals(PlaybackKind.EPISODE, playbackKindOf(Section.SERIES))
    }
}

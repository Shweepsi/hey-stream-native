package io.github.shweepsi.heystream.ui.search

import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.Progress
import io.github.shweepsi.heystream.core.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {
    @Test
    fun termNeedsTwoCharacters() {
        assertNull(searchTerm(""))
        assertNull(searchTerm("   "))
        assertNull(searchTerm(" a "))
        assertEquals("ab", searchTerm("ab"))
        assertEquals("le monde", searchTerm("  le monde "))
        // Deux caractères, pas deux unités UTF-16.
        assertEquals("é1", searchTerm("é1"))
        assertNull(searchTerm("🎬"))
    }

    @Test
    fun hitKeepsProviderDataAndProgress() {
        val movie = item(Section.MOVIES, 7, progress = Progress(positionMs = 30_000, durationMs = 120_000), watched = false)
        val hit = movie.toHit()
        assertEquals(Section.MOVIES, hit.section)
        assertEquals(7L, hit.id)
        assertEquals(0.25f, hit.progress!!, 0.0001f)
        assertFalse(hit.watched)
        assertEquals("MOVIES:7", hit.key)
    }

    @Test
    fun noBarWithoutProgressOrDuration() {
        assertNull(item(Section.SERIES, 1).toHit().progress)
        assertNull(item(Section.SERIES, 1, progress = Progress(positionMs = 5_000, durationMs = 0)).toHit().progress)
        assertTrue(item(Section.MOVIES, 2, watched = true).toHit().watched)
    }

    @Test
    fun channelKeepsNumber() {
        val hit = item(Section.LIVE, 3, number = 101).toHit()
        assertEquals(101L, hit.number)
        assertNull(item(Section.LIVE, 4).toHit().number)
    }

    @Test
    fun emptyResultsWhileDownloadingShowTheDownload() {
        assertEquals(SearchResults.Downloading, resultsOf("abc", emptyList(), emptyList(), emptyList(), downloading = true))
        val empty = resultsOf("abc", emptyList(), emptyList(), emptyList(), downloading = false)
        assertTrue(empty is SearchResults.Found && empty.isEmpty)
        val one = listOf(item(Section.LIVE, 1).toHit())
        val found = resultsOf("abc", one, emptyList(), emptyList(), downloading = true)
        assertTrue(found is SearchResults.Found && !found.isEmpty)
    }

    private fun item(
        section: Section,
        id: Long,
        number: Long? = null,
        progress: Progress? = null,
        watched: Boolean = false,
    ) = CatalogItem(
        section = section,
        id = id,
        name = "Titre $id",
        image = null,
        categoryId = "1",
        extension = null,
        epgChannelId = null,
        rating = null,
        plot = null,
        number = number,
        progress = progress,
        watched = watched,
    )
}

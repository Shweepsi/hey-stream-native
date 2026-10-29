package io.github.shweepsi.heystream.ui.detail

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.Episode
import io.github.shweepsi.heystream.core.MovieInfo
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Progress
import io.github.shweepsi.heystream.core.Resume
import io.github.shweepsi.heystream.core.Season
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SeriesInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailLogicTest {
    private fun item(
        name: String = "Nord Express",
        image: String? = "poster-cache",
        rating: String? = null,
        plot: String? = null,
        extension: String? = "mkv",
        progress: Progress? = null,
        watched: Boolean = false,
    ) = CatalogItem(
        section = Section.MOVIES,
        id = 7,
        name = name,
        image = image,
        categoryId = "1",
        extension = extension,
        epgChannelId = null,
        rating = rating,
        plot = plot,
        number = null,
        progress = progress,
        watched = watched,
    )

    private fun info(
        name: String? = "Nord Express (2025)",
        plot: String? = "Résumé de la fiche",
        year: String? = "2025",
        duration: Long? = 6_720,
        rating: String? = "7.4",
        image: String? = "poster-info",
    ) = MovieInfo(name, plot, year, duration, rating, image, "mp4")

    @Test
    fun metaLineShowsOnlyProvidedFields() {
        assertEquals(
            listOf(MetaPart.Year("2025"), MetaPart.Duration(1, 52), MetaPart.Rating("7.4")),
            metaParts(DetailHeader("A", year = "2025", durationSecs = 6_720, rating = "7.4")),
        )
        assertEquals(listOf(MetaPart.Duration(0, 45)), metaParts(DetailHeader("A", year = " ", durationSecs = 2_690, rating = "0")))
        assertEquals(emptyList<MetaPart>(), metaParts(DetailHeader("A", durationSecs = 0)))
    }

    @Test
    fun zeroOrBlankRatingIsHidden() {
        assertNull(displayRating(null))
        assertNull(displayRating(""))
        assertNull(displayRating("  "))
        assertNull(displayRating("0"))
        assertNull(displayRating("0.0"))
        assertNull(displayRating("0,0"))
        assertEquals("7,2", displayRating(" 7,2 "))
        assertEquals("PG", displayRating("PG"))
    }

    @Test
    fun movieHeaderStartsFromTheCacheThenTheProviderFillsIn() {
        val cached = movieHeader(item(rating = "6"), null)!!
        assertEquals("Nord Express", cached.title)
        assertEquals("poster-cache", cached.image)
        assertEquals("6", cached.rating)
        assertNull(cached.year)

        val enriched = movieHeader(item(rating = "6", plot = "court"), info())!!
        assertEquals("Nord Express", enriched.title)
        // Affiche de la grille gardée : pas de second chargement.
        assertEquals("poster-cache", enriched.image)
        assertEquals("Résumé de la fiche", enriched.plot)
        assertEquals("2025", enriched.year)
        assertEquals(6_720L, enriched.durationSecs)
        assertEquals("7.4", enriched.rating)

        val infoOnly = movieHeader(null, info(rating = " "))!!
        assertEquals("Nord Express (2025)", infoOnly.title)
        assertEquals("poster-info", infoOnly.image)
        assertNull(infoOnly.rating)

        assertNull(movieHeader(null, null))
        assertNull(movieHeader(null, info(name = null)))
    }

    @Test
    fun movieProgressUsesTheCatalogThenTheFicheDuration() {
        assertEquals(WatchProgress(4_040_000, 6_740_000), movieProgress(item(progress = Progress(4_040_000, 6_740_000)), null, null))
        assertEquals(WatchProgress(60_000, 6_720_000), movieProgress(item(), 60_000, 6_720))
        assertEquals(WatchProgress(60_000, 0), movieProgress(null, 60_000, null))
        assertNull(movieProgress(item(), null, 6_720))
        assertEquals(45L, WatchProgress(4_040_000, 6_740_000).remainingMinutes)
        assertNull(WatchProgress(60_000, 0).remainingMinutes)
        assertNull(WatchProgress(60_000, 0).fraction)
    }

    private fun episode(id: Long, season: Long, number: Long?, title: String? = "Épisode $id", duration: Long? = 2_640) =
        Episode(id, season, number, title, "mkv", null, null, duration)

    private val series = SeriesInfo(
        name = "Les Veilleurs",
        plot = "Résumé",
        year = "2023",
        rating = "8.1",
        cover = "cover",
        seasons = listOf(
            Season(1, null, listOf(episode(11, 1, 1), episode(12, 1, 2))),
            Season(2, "Saison 2", listOf(episode(21, 2, null), episode(22, 2, null, title = " ", duration = null))),
        ),
    )

    private fun resume(id: Long, position: Long, duration: Long) =
        Resume(PlaybackKind.EPISODE, id, position, duration, "Les Veilleurs", null, "mkv", 5)

    @Test
    fun episodeRowsCarryTheirStateAndNumber() {
        val rows = episodeRows(series, watched = setOf(11L), positions = mapOf(12L to 600_000L, 22L to 90_000L), resume = resume(22, 90_000, 1_800_000))
        assertEquals(listOf(11L, 12L, 21L, 22L), rows.map { it.id })
        assertTrue(rows[0].watched)
        assertNull(rows[0].progress)
        assertEquals(WatchProgress(600_000, 2_640_000), rows[1].progress)
        // Sans numéro du fournisseur : sa place dans la saison.
        assertEquals(listOf(1L, 2L, 1L, 2L), rows.map { it.number })
        assertNull(rows[3].title)
        // Sans durée dans la fiche : celle de la reprise.
        assertEquals(WatchProgress(90_000, 1_800_000), rows[3].progress)
        assertNull(rows[3].durationSecs)
    }

    @Test
    fun anEpisodeRepeatedByThePanelIsListedOnce() {
        // Même identifiant sous deux numéros : une seule ligne (clé de la liste).
        val repeated = series.copy(
            seasons = listOf(Season(1, null, listOf(episode(11, 1, 1), episode(12, 1, 2), episode(11, 1, 3)))),
        )
        assertEquals(listOf(11L, 12L), episodeRows(repeated, emptySet(), emptyMap(), null).map { it.id })
    }

    @Test
    fun missingEntryIsTreatedAsLockedWhileParentalControlIsOn() {
        // Verrou fermé et entrée absente (verrouillée) : ni fiche ni flux demandés.
        assertTrue(lockedOut(null, parentalUnlocked = false))
        // Déverrouillé pour la session : la fiche du fournisseur peut compléter.
        assertFalse(lockedOut(null, parentalUnlocked = true))
        assertFalse(lockedOut(item(), parentalUnlocked = false))
    }

    @Test
    fun firstButtonResumesTheEpisodeInProgressElseTheFirstUnwatched() {
        val rows = episodeRows(series, watched = setOf(11L, 12L), positions = emptyMap(), resume = null)
        assertEquals(PlayTarget(21, 2, 1, resume = false), playTarget(rows, resumeEpisodeId = null))
        assertEquals(PlayTarget(12, 1, 2, resume = true), playTarget(rows, resumeEpisodeId = 12))
        // Épisode en cours absent de la fiche : premier non vu.
        assertEquals(PlayTarget(21, 2, 1, resume = false), playTarget(rows, resumeEpisodeId = 99))

        val allWatched = episodeRows(series, watched = setOf(11L, 12L, 21L, 22L), positions = emptyMap(), resume = null)
        assertEquals(PlayTarget(11, 1, 1, resume = false), playTarget(allWatched, null))
        assertNull(playTarget(emptyList(), null))
    }

    @Test
    fun episodeInProgressCarriesItsProgressForTheFiche() {
        // « Vu jusqu’à … » de l'épisode repris ; sa durée vient de la reprise s'il n'en a pas.
        val rows = episodeRows(series, watched = setOf(11L), positions = mapOf(12L to 600_000L, 22L to 90_000L), resume = resume(22, 90_000, 1_800_000))
        assertEquals(PlayTarget(22, 2, 2, resume = true, progress = WatchProgress(90_000, 1_800_000)), playTarget(rows, resumeEpisodeId = 22))
        // Pas d'épisode repris : « Lecture », sans progression même si un épisode est commencé.
        assertEquals(PlayTarget(12, 1, 2, resume = false), playTarget(rows, resumeEpisodeId = null))
    }

    @Test
    fun seasonShownKeepsTheChoiceElseFollowsTheFirstButton() {
        val target = PlayTarget(21, 2, 1, resume = true)
        assertEquals(2L, chooseSeason(listOf(1, 2), current = null, target = target))
        assertEquals(1L, chooseSeason(listOf(1, 2), current = 1, target = target))
        assertEquals(1L, chooseSeason(listOf(1, 2), current = 5, target = null))
        assertNull(chooseSeason(emptyList(), current = null, target = null))
    }

    @Test
    fun seriesHeaderTakesYearAndPlotFromTheFiche() {
        val cached = CatalogItem(Section.SERIES, 5, "Les Veilleurs du large", "poster", "3", null, null, "7", null, null, null, false)
        val header = seriesHeader(cached, series)!!
        assertEquals("Les Veilleurs du large", header.title)
        assertEquals("poster", header.image)
        assertEquals("Résumé", header.plot)
        assertEquals("2023", header.year)
        assertEquals("8.1", header.rating)
        assertNull(header.durationSecs)
        assertEquals("cover", seriesHeader(null, series)!!.image)
        assertFalse(seriesHeader(cached, null)!!.year != null)
    }
}

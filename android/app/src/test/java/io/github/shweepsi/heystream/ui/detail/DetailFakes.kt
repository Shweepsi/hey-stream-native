package io.github.shweepsi.heystream.ui.detail

import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.UiText

/** Données fictives des fiches (aucun fournisseur réel, aucune image réseau). */
object DetailFakes {
    private const val PLOT =
        "Un train de nuit traverse l’Europe sous la neige. À son bord, une inspectrice en congé " +
            "découvre qu’un passager a disparu entre deux gares, et que personne ne semble s’en " +
            "souvenir. Elle a jusqu’à l’arrivée pour comprendre ce qui s’est passé, avant que le " +
            "train ne franchisse la dernière frontière et que toute trace ne s’efface."

    val startedMovie = MovieDetailState(
        loading = false,
        movie = MovieDetail(
            header = DetailHeader(
                title = "Nord Express",
                plot = PLOT,
                year = "2025",
                durationSecs = 6_720,
                rating = "7.4",
            ),
            progress = WatchProgress(positionMs = 4_040_000, durationMs = 6_740_000),
            watched = false,
            favorite = true,
        ),
    )

    val newMovie = MovieDetailState(
        loading = false,
        movie = MovieDetail(
            header = DetailHeader(title = "Les Jardins suspendus", plot = "Une botaniste hérite d’une serre abandonnée.", rating = "0"),
            watched = true,
        ),
    )

    val movieError = MovieDetailState(loading = false, error = UiText(R.string.error_network))

    private fun episode(id: Long, season: Long, number: Long, title: String?, progress: WatchProgress? = null, watched: Boolean = false) =
        EpisodeRow(
            id = id,
            season = season,
            number = number,
            title = title,
            durationSecs = 2_640,
            progress = progress,
            watched = watched,
        )

    private val season2 = listOf(
        episode(21, 2, 1, "Le retour", watched = true),
        episode(22, 2, 2, "La traversée", watched = true),
        episode(23, 2, 3, "Le phare", progress = WatchProgress(1_320_000, 2_640_000)),
        episode(24, 2, 4, "Marée haute"),
        episode(25, 2, 5, null),
        episode(26, 2, 6, "Les signaux"),
    )

    val series = SeriesDetailState(
        header = DetailHeader(
            title = "Les Veilleurs du large",
            plot = "Sur une île coupée du continent, les gardiens d’un phare reçoivent des messages venus d’un navire disparu depuis trente ans.",
            year = "2023",
            rating = "8.1",
        ),
        loading = false,
        favorite = false,
        watched = false,
        episodesLoading = false,
        seasons = listOf(1, 2, 3),
        selectedSeason = 2,
        episodes = season2,
        playTarget = PlayTarget(episodeId = 23, season = 2, number = 3, resume = true),
    )

    val seriesLoadingEpisodes = SeriesDetailState(
        header = DetailHeader(title = "Les Veilleurs du large", rating = "8.1"),
        loading = false,
        episodesLoading = true,
    )
}

package io.github.shweepsi.heystream.ui.detail

import androidx.compose.runtime.Immutable
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.MovieInfo
import io.github.shweepsi.heystream.core.Resume
import io.github.shweepsi.heystream.core.SeriesInfo
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.UiText

/**
 * En-tête d'une fiche : seulement les champs fournis par le fournisseur
 * (un champ absent disparaît, docs/DESIGN.md).
 */
@Immutable
data class DetailHeader(
    val title: String,
    val image: String? = null,
    val plot: String? = null,
    val year: String? = null,
    val durationSecs: Long? = null,
    val rating: String? = null,
)

/** Lecture commencée ; [durationMs] vaut 0 si la durée est inconnue. */
@Immutable
data class WatchProgress(val positionMs: Long, val durationMs: Long) {
    /** Avancement entre 0 et 1, ou `null` sans durée connue. */
    val fraction: Float?
        get() = if (durationMs > 0) Formats.fraction(positionMs, durationMs) else null

    /** Minutes restantes, ou `null` sans durée connue. */
    val remainingMinutes: Long?
        get() = if (durationMs > 0) Formats.remainingMinutes(positionMs, durationMs) else null
}

/** Élément de la ligne d'infos d'une fiche : année · durée · note. */
sealed interface MetaPart {
    data class Year(val text: String) : MetaPart

    data class Duration(val hours: Long, val minutes: Long) : MetaPart

    data class Rating(val text: String) : MetaPart
}

/** Ligne d'infos : seulement les champs présents, dans l'ordre année, durée, note. */
fun metaParts(header: DetailHeader): List<MetaPart> = buildList {
    header.year?.trim()?.takeIf { it.isNotEmpty() }?.let { add(MetaPart.Year(it)) }
    header.durationSecs?.takeIf { it > 0 }?.let { secs ->
        val (hours, minutes) = Formats.hoursMinutes(secs)
        if (hours > 0 || minutes > 0) add(MetaPart.Duration(hours, minutes))
    }
    displayRating(header.rating)?.let { add(MetaPart.Rating(it)) }
}

/** Note du fournisseur, telle quelle ; absente si vide ou nulle (« 0 », « 0.0 »). */
fun displayRating(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val value = text.replace(',', '.').toDoubleOrNull()
    return if (value != null && value == 0.0) null else text
}

internal fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

// Film ---------------------------------------------------------------------------

@Immutable
data class MovieDetail(
    val header: DetailHeader,
    val progress: WatchProgress? = null,
    val watched: Boolean = false,
    val favorite: Boolean = false,
)

@Immutable
data class MovieDetailState(
    val movie: MovieDetail? = null,
    /** Rien à afficher pour l'instant (cache vide, fiche demandée). */
    val loading: Boolean = true,
    /** Ni cache ni fiche : message et « Réessayer ». */
    val error: UiText? = null,
)

/**
 * En-tête d'un film : le cache d'abord (affiché aussitôt), enrichi par la
 * fiche du fournisseur. `null` si rien n'est connu.
 */
internal fun movieHeader(item: CatalogItem?, info: MovieInfo?): DetailHeader? {
    val title = item?.name.nonBlank() ?: info?.name.nonBlank() ?: return null
    return DetailHeader(
        title = title,
        // L'affiche de la grille (déjà en cache d'images) : pas de second chargement.
        image = item?.image.nonBlank() ?: info?.image.nonBlank(),
        plot = info?.plot.nonBlank() ?: item?.plot.nonBlank(),
        year = info?.year.nonBlank(),
        durationSecs = info?.durationSecs?.takeIf { it > 0 },
        rating = info?.rating.nonBlank() ?: item?.rating.nonBlank(),
    )
}

/**
 * Lecture commencée d'un film : celle du catalogue (position et durée),
 * sinon la seule position ; la durée de la fiche complète une durée inconnue.
 */
internal fun movieProgress(item: CatalogItem?, positionMs: Long?, durationSecs: Long?): WatchProgress? {
    val progress = item?.progress?.let { WatchProgress(it.positionMs, it.durationMs) }
        ?: positionMs?.let { WatchProgress(it, 0) }
        ?: return null
    if (progress.durationMs > 0 || durationSecs == null || durationSecs <= 0) return progress
    return progress.copy(durationMs = durationSecs * 1000)
}

// Série ----------------------------------------------------------------------------

/** Épisode d'une saison, avec son état (vu, commencé). */
@Immutable
data class EpisodeRow(
    val id: Long,
    val season: Long,
    /** Numéro du fournisseur, sinon sa place dans la saison. */
    val number: Long,
    val title: String?,
    val durationSecs: Long?,
    val progress: WatchProgress?,
    val watched: Boolean,
)

/** Premier bouton d'une série : « Reprendre S2 · E3 » ou « Lecture ». */
@Immutable
data class PlayTarget(
    val episodeId: Long,
    val season: Long,
    val number: Long,
    /** Épisode commencé (dernier regardé de la série). */
    val resume: Boolean,
)

@Immutable
data class SeriesDetailState(
    val header: DetailHeader? = null,
    /** Rien à afficher pour l'instant. */
    val loading: Boolean = true,
    /** Ni cache ni fiche : message et « Réessayer ». */
    val error: UiText? = null,
    val favorite: Boolean = false,
    /** Tous les épisodes connus sont vus. */
    val watched: Boolean = false,
    /** Liste des épisodes demandée au fournisseur. */
    val episodesLoading: Boolean = true,
    /** Liste des épisodes indisponible : le premier bouton devient « Réessayer ». */
    val episodesError: UiText? = null,
    val seasons: List<Long> = emptyList(),
    val selectedSeason: Long? = null,
    /** Épisodes de la saison choisie. */
    val episodes: List<EpisodeRow> = emptyList(),
    val playTarget: PlayTarget? = null,
)

/**
 * En-tête d'une série : le cache d'abord, enrichi par la fiche (année,
 * résumé, note). `null` si rien n'est connu.
 */
internal fun seriesHeader(item: CatalogItem?, info: SeriesInfo?): DetailHeader? {
    val title = item?.name.nonBlank() ?: info?.name.nonBlank() ?: return null
    return DetailHeader(
        title = title,
        image = item?.image.nonBlank() ?: info?.cover.nonBlank(),
        plot = info?.plot.nonBlank() ?: item?.plot.nonBlank(),
        year = info?.year.nonBlank(),
        rating = info?.rating.nonBlank() ?: item?.rating.nonBlank(),
    )
}

/**
 * Épisodes de toutes les saisons, dans l'ordre de la fiche, avec leur état.
 * [resume] : dernier épisode commencé de la série (sa durée complète celle
 * d'un épisode sans durée connue).
 */
internal fun episodeRows(
    info: SeriesInfo,
    watched: Set<Long>,
    positions: Map<Long, Long>,
    resume: Resume?,
): List<EpisodeRow> = info.seasons.flatMap { season ->
    season.episodes.mapIndexed { index, episode ->
        val durationSecs = episode.durationSecs?.takeIf { it > 0 }
        val position = positions[episode.id]
        val durationMs = when {
            durationSecs != null -> durationSecs * 1000
            resume != null && resume.id == episode.id -> resume.durationMs.coerceAtLeast(0)
            else -> 0
        }
        EpisodeRow(
            id = episode.id,
            season = season.number,
            number = episode.number ?: (index + 1).toLong(),
            title = episode.title.nonBlank(),
            durationSecs = durationSecs,
            progress = position?.let { WatchProgress(it, durationMs) },
            watched = episode.id in watched,
        )
    }
}

/**
 * Épisode du premier bouton : celui en cours ([resumeEpisodeId], « Reprendre
 * S2 · E3 »), sinon le premier non vu, sinon le premier.
 */
internal fun playTarget(rows: List<EpisodeRow>, resumeEpisodeId: Long?): PlayTarget? {
    rows.firstOrNull { it.id == resumeEpisodeId }?.let {
        return PlayTarget(it.id, it.season, it.number, resume = true)
    }
    val row = rows.firstOrNull { !it.watched } ?: rows.firstOrNull() ?: return null
    return PlayTarget(row.id, row.season, row.number, resume = false)
}

/** Saison montrée : celle déjà choisie si elle existe, sinon celle du premier bouton, sinon la première. */
internal fun chooseSeason(seasons: List<Long>, current: Long?, target: PlayTarget?): Long? = when {
    current != null && current in seasons -> current
    target != null && target.season in seasons -> target.season
    else -> seasons.firstOrNull()
}

package io.github.shweepsi.heystream.ui.nav

import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Section

/** Écrans de l'app. Les arguments sont des identifiants, jamais des URL. */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** Direct ; `channel` : chaîne à ouvrir en plein écran (depuis la recherche). */
    const val LIVE = "live?channel={channel}"
    fun live(channel: Long? = null) = if (channel == null) "live" else "live?channel=$channel"

    /** Films (`MOVIES`) ou séries (`SERIES`). */
    const val VOD = "vod/{section}"
    fun vod(section: Section) = "vod/${section.name}"

    const val MOVIE = "movie/{id}"
    fun movie(id: Long) = "movie/$id"

    const val SERIES = "series/{id}"
    fun series(id: Long) = "series/$id"

    /** Lecture d'un film ou d'un épisode ; `fromStart` : ignorer la reprise. */
    const val PLAYER = "player/{kind}/{id}?seriesId={seriesId}&fromStart={fromStart}"
    fun player(kind: PlaybackKind, id: Long, seriesId: Long? = null, fromStart: Boolean = false) =
        "player/${kind.name}/$id?seriesId=${seriesId ?: -1}&fromStart=$fromStart"
}

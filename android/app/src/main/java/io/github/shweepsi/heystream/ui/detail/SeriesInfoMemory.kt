package io.github.shweepsi.heystream.ui.detail

import android.os.SystemClock
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.SeriesInfo

/**
 * Dernière fiche série chargée, gardée quelques minutes : le lecteur qui
 * s'ouvre depuis la fiche y lit l'épisode (extension, titre) sans seconde
 * requête au fournisseur. Une seule fiche en mémoire (docs/MATERIEL.md).
 */
internal object SeriesInfoMemory {
    /** Au-delà, la fiche est redemandée (épisodes ajoutés entre-temps). */
    private const val FRESH_MS = 30 * 60 * 1000L

    private class Entry(val seriesId: Long, val info: SeriesInfo, val at: Long)

    @Volatile
    private var last: Entry? = null

    fun remember(seriesId: Long, info: SeriesInfo) {
        last = Entry(seriesId, info, SystemClock.elapsedRealtime())
    }

    fun get(seriesId: Long): SeriesInfo? =
        last?.takeIf { it.seriesId == seriesId && SystemClock.elapsedRealtime() - it.at < FRESH_MS }?.info

    /** Fiche d'une série : en mémoire si récente, sinon demandée (appel bloquant, réseau). */
    fun load(core: HeyStreamCore, seriesId: Long, fresh: Boolean = false): SeriesInfo {
        if (!fresh) get(seriesId)?.let { return it }
        return core.seriesInfo(seriesId).also { remember(seriesId, it) }
    }
}

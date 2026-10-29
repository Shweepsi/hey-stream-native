package io.github.shweepsi.heystream.ui.detail

import android.os.SystemClock
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.SeriesInfo
import java.lang.ref.WeakReference

/**
 * Dernière fiche série chargée, gardée quelques minutes : le lecteur qui
 * s'ouvre depuis la fiche y lit l'épisode (extension, titre) sans seconde
 * requête au fournisseur. Une seule fiche en mémoire (docs/MATERIEL.md),
 * liée au cœur qui l'a chargée : après une reconnexion (autre abonnement),
 * elle n'est plus utilisée.
 */
internal object SeriesInfoMemory {
    /** Au-delà, la fiche est redemandée (épisodes ajoutés entre-temps). */
    private const val FRESH_MS = 30 * 60 * 1000L

    private class Entry(val core: WeakReference<HeyStreamCore>, val seriesId: Long, val info: SeriesInfo, val at: Long)

    @Volatile
    private var last: Entry? = null

    private fun isFresh(entry: Entry, seriesId: Long): Boolean =
        entry.seriesId == seriesId && SystemClock.elapsedRealtime() - entry.at < FRESH_MS

    /** Fiche en mémoire, quel que soit le cœur : seulement pour un libellé (numéro d'épisode). */
    fun get(seriesId: Long): SeriesInfo? = last?.takeIf { isFresh(it, seriesId) }?.info

    /** Fiche d'une série : en mémoire si récente, sinon demandée (appel bloquant, réseau). */
    fun load(core: HeyStreamCore, seriesId: Long, fresh: Boolean = false): SeriesInfo {
        if (!fresh) {
            last?.takeIf { isFresh(it, seriesId) && it.core.get() === core }?.let { return it.info }
        }
        return core.seriesInfo(seriesId).also {
            last = Entry(WeakReference(core), seriesId, it, SystemClock.elapsedRealtime())
        }
    }
}

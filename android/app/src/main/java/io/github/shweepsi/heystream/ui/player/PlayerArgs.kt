package io.github.shweepsi.heystream.ui.player

import androidx.lifecycle.SavedStateHandle
import io.github.shweepsi.heystream.core.PlaybackKind

/**
 * Le titre que lit l'écran, gardé dans le `SavedStateHandle` de son entrée
 * de navigation : c'est lui qui fait foi, pas les arguments de la route.
 *
 * Sur la Mi Box S (2 Go), Android arrête souvent l'app en arrière-plan ; au
 * retour, l'écran est restauré avec les arguments **d'origine** de la route.
 * Le handle, lui, est restauré tel qu'il a été laissé :
 * - la position atteinte par l'écran ([reached]) y est gardée : une
 *   restauration reprend là, au lieu de repartir de 0 après un « Depuis le
 *   début » (et d'écraser la reprise) ou de sauter à l'ancienne reprise que
 *   le cœur garde tant que 30 s n'ont pas été vues ;
 * - l'épisode enchaîné automatiquement remplace celui de la route : une
 *   restauration rouvre celui qu'on regardait, pas le premier.
 *
 * Mêmes clés que les arguments de `Routes.PLAYER`, que la navigation copie
 * dans le handle à sa création.
 */
internal class PlayerArgs(private val handle: SavedStateHandle) {
    val kind: PlaybackKind
        get() = handle.get<String>(KIND)?.let { name -> PlaybackKind.entries.firstOrNull { it.name == name } }
            ?: PlaybackKind.MOVIE

    val id: Long
        get() = handle[ID] ?: 0L

    /** Série de l'épisode ; `null` pour un film (la route donne alors -1). */
    val seriesId: Long?
        get() = handle.get<Long>(SERIES_ID)?.takeIf { it >= 0 }

    /** « Depuis le début » : la position enregistrée est ignorée. */
    val fromStart: Boolean
        get() = handle[FROM_START] ?: false

    /** Dernière position enregistrée par l'écran pour ce titre ; `null` avant la première. */
    val positionMs: Long?
        get() = handle[POSITION_MS]

    /**
     * Où ouvrir le titre, ou `null` pour la reprise enregistrée par le cœur :
     * la position atteinte par l'écran (restauré après l'arrêt de l'app),
     * sinon 0 pour « Depuis le début ».
     *
     * La position de l'écran passe avant le cœur, qui ne retient rien sous
     * 30 s : « Depuis le début », l'app arrêtée au bout de 20 s, puis le
     * retour ne ramènent pas à l'ancienne reprise. Tant que l'écran n'a rien
     * enregistré (flux en chargement), « Depuis le début » vaut toujours.
     */
    val startMs: Long?
        get() = positionMs ?: if (fromStart) 0L else null

    /** Progression enregistrée (lecture, pause, arrière-plan) : une restauration reprend là. */
    fun reached(positionMs: Long) {
        handle[POSITION_MS] = positionMs
    }

    /** Épisode suivant enchaîné : c'est lui qu'une restauration rouvre, à sa position enregistrée. */
    fun advanceTo(episodeId: Long) {
        handle[KIND] = PlaybackKind.EPISODE.name
        handle[ID] = episodeId
        handle[FROM_START] = false
        // La position atteinte était celle de l'épisode précédent.
        handle.remove<Long>(POSITION_MS)
    }

    companion object {
        const val KIND = "kind"
        const val ID = "id"
        const val SERIES_ID = "seriesId"
        const val FROM_START = "fromStart"

        /** Propre à l'écran (pas un argument de la route). */
        const val POSITION_MS = "positionMs"

        /**
         * Remplit [handle] avec les arguments de la route, seulement pour les
         * clés absentes : un handle restauré garde ses valeurs (titre en cours,
         * position atteinte) ; un handle créé hors navigation reçoit tout.
         */
        fun seed(handle: SavedStateHandle, kind: PlaybackKind, id: Long, seriesId: Long?, fromStart: Boolean): SavedStateHandle {
            if (!handle.contains(KIND)) handle[KIND] = kind.name
            if (!handle.contains(ID)) handle[ID] = id
            if (!handle.contains(SERIES_ID)) handle[SERIES_ID] = seriesId ?: -1L
            if (!handle.contains(FROM_START)) handle[FROM_START] = fromStart
            return handle
        }
    }
}

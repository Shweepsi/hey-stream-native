package io.github.shweepsi.heystream.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import io.github.shweepsi.heystream.core.Episode
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Resume
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SeriesInfo
import io.github.shweepsi.heystream.core.StreamKind
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.detail.SeriesInfoMemory
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Épisode d'une série : « S1 · E3 » et son titre. */
@Immutable
data class EpisodeLabel(val season: Long, val number: Long, val title: String?)

/** Compte à rebours de l'épisode suivant (spec : 10 s, annulable). */
@Immutable
data class NextEpisodeUi(val label: EpisodeLabel, val secondsLeft: Int)

/** Ce que l'écran de lecture affiche, hors position (lue sur le lecteur). */
@Immutable
data class PlayerUiState(
    /** Titre du film, ou nom de la série. */
    val title: String = "",
    val episode: EpisodeLabel? = null,
    /** Adresse du flux en cours de préparation. */
    val resolving: Boolean = true,
    /** Le flux n'a pas pu être préparé (fiche introuvable, réseau). */
    val error: UiText? = null,
    /** « Réessayer » après un échec du lecteur, en attendant sa réponse. */
    val retrying: Boolean = false,
    val next: NextEpisodeUi? = null,
    val tracks: TrackLists = TrackLists(),
    /** Fin de lecture ou « Annuler » : l'écran se ferme. */
    val exit: Boolean = false,
)

/** Ce que le lecteur doit ouvrir. L'URL ne sort jamais de ce fichier (ni journal, ni état). */
private class Media(
    val kind: PlaybackKind,
    val id: Long,
    val title: String,
    val episode: EpisodeLabel?,
    val image: String?,
    val extension: String?,
    /** Durée de la fiche, si le lecteur ne la connaît pas encore. */
    val durationMs: Long,
)

private class Resolved(val media: Media, val url: String, val startMs: Long)

/** Toutes les 10 s pendant la lecture, la position est enregistrée. */
private const val SAVE_EVERY_MS = 10_000L

/** Compte à rebours avant l'épisode suivant. */
const val NEXT_EPISODE_SECONDS = 10

/** Entrées « Continuer » lues pour retrouver un épisode sans fiche. */
private const val CONTINUE_LIMIT = 200u

/**
 * Lecture d'un film ou d'un épisode : prépare l'adresse du flux, reprend à
 * la dernière position, enregistre la progression (toutes les 10 s, à la
 * pause, à la sortie) et enchaîne l'épisode suivant. Le cœur marque le
 * titre « vu » en fin de lecture : rien n'est décidé ici.
 *
 * Utilisé depuis le fil principal (lecteur Media3).
 */
class PlayerViewModel(
    private val session: CoreSession,
    private val controller: PlayerController,
    kind: PlaybackKind,
    id: Long,
    private val seriesId: Long?,
    fromStart: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var kind = kind
    private var id = id
    private var media: Media? = null

    /** Le flux de [media] a été donné au lecteur (son état le concerne). */
    private var started = false
    private var endHandled = false
    private var wasPlaying = false
    private var closed = false
    private var resolveJob: Job? = null
    private var nextJob: Job? = null

    /** Épisode annoncé par le compte à rebours. */
    private var pendingNext: Episode? = null

    private val player: Player get() = controller.player

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.update { it.copy(tracks = trackLists(tracks)) }
        }
    }

    init {
        player.addListener(listener)
        resolve(fromStart)
        viewModelScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (started && !closed && controller.status.value.playing) saveProgress()
            }
        }
        viewModelScope.launch { controller.status.collect(::onStatus) }
    }

    // Commandes ---------------------------------------------------------------

    fun togglePause() {
        val player = player
        if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)) {
            player.pause()
        } else {
            player.play()
        }
    }

    /** Avance ou recul rapide (±10 s), dans les limites du flux. */
    fun seekBy(deltaMs: Long) {
        val player = player
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        player.seekTo(if (duration != null) target.coerceAtMost(duration - 1_000).coerceAtLeast(0) else target)
    }

    /** Barre de progression touchée ou glissée au doigt. */
    fun seekToFraction(fraction: Float) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun selectTrack(key: TrackKey) {
        val player = player
        player.trackSelectionParameters = selectTrack(player.trackSelectionParameters, player.currentTracks, key)
    }

    fun disableSubtitles() {
        val player = player
        player.trackSelectionParameters = disableSubtitles(player.trackSelectionParameters)
    }

    /** « Réessayer » : flux coupé (lecteur) ou adresse introuvable (fiche). */
    fun retry() {
        if (_state.value.error != null || !started) {
            resolve(fromStart = false)
        } else {
            _state.update { it.copy(retrying = true) }
            controller.retry()
        }
    }

    /** « Lire maintenant » ou fin du compte à rebours. */
    fun playNextNow() {
        val job = nextJob ?: return
        job.cancel()
        nextJob = null
        val next = pendingNext ?: return
        pendingNext = null
        _state.update { it.copy(next = null) }
        kind = PlaybackKind.EPISODE
        id = next.id
        resolve(fromStart = false)
    }

    /** « Annuler » l'épisode suivant : l'écran se ferme. */
    fun cancelNext() {
        nextJob?.cancel()
        nextJob = null
        pendingNext = null
        _state.update { it.copy(next = null, exit = true) }
    }

    /**
     * Sortie de l'écran : position enregistrée, flux fermé. Sans effet la
     * seconde fois (Retour puis fin du ViewModel).
     */
    fun close() {
        if (closed) return
        closed = true
        resolveJob?.cancel()
        nextJob?.cancel()
        if (started && !endHandled) saveProgress()
        player.removeListener(listener)
        controller.stop()
    }

    /** L'app passe en arrière-plan (le lecteur ferme alors le flux) : position enregistrée. */
    fun onBackground() {
        if (started && !endHandled && !closed) saveProgress()
    }

    override fun onCleared() {
        close()
    }

    // Lecture -------------------------------------------------------------------

    private fun resolve(fromStart: Boolean) {
        resolveJob?.cancel()
        started = false
        endHandled = false
        wasPlaying = false
        _state.update { it.copy(resolving = true, error = null, retrying = false, next = null, tracks = TrackLists()) }
        val kind = kind
        val id = id
        resolveJob = viewModelScope.launch {
            try {
                val resolved = session.withCore { core -> resolveMedia(core, kind, id, fromStart) }
                if (closed) return@launch
                media = resolved.media
                _state.update {
                    it.copy(title = resolved.media.title, episode = resolved.media.episode, resolving = false, error = null)
                }
                controller.playVod(resolved.url, resolved.startMs)
                started = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                _state.update { it.copy(resolving = false, error = failure.toUiText()) }
            }
        }
    }

    /** Appel bloquant (cœur) : fiche, extension, position de reprise, récents. */
    private fun resolveMedia(core: HeyStreamCore, kind: PlaybackKind, id: Long, fromStart: Boolean): Resolved {
        val media = when (kind) {
            PlaybackKind.MOVIE -> movieMedia(core, id)
            PlaybackKind.EPISODE -> episodeMedia(core, id, seriesId ?: throw HeyStreamException.NotFound())
        }
        val streamKind = if (kind == PlaybackKind.MOVIE) StreamKind.MOVIE else StreamKind.SERIES
        val url = core.streamUrl(streamKind, id, media.extension)
        val start = if (fromStart) 0L else core.progress(kind, id) ?: 0L
        // Secondaire : un échec n'empêche pas la lecture.
        runCatching {
            if (kind == PlaybackKind.MOVIE) core.addRecent(Section.MOVIES, id) else seriesId?.let { core.addRecent(Section.SERIES, it) }
        }
        return Resolved(media, url, start.coerceAtLeast(0))
    }

    private fun movieMedia(core: HeyStreamCore, id: Long): Media {
        val item = core.item(Section.MOVIES, id)
        // La fiche (réseau) seulement si le catalogue ne donne pas l'extension.
        val info = if (item?.extension.isNullOrBlank()) {
            try {
                core.movieInfo(id)
            } catch (failure: HeyStreamException) {
                if (item == null) throw failure else null
            }
        } else {
            null
        }
        return Media(
            kind = PlaybackKind.MOVIE,
            id = id,
            title = item?.name?.takeIf { it.isNotBlank() } ?: info?.name.orEmpty(),
            episode = null,
            image = item?.image ?: info?.image,
            extension = item?.extension?.takeIf { it.isNotBlank() } ?: info?.extension,
            durationMs = (info?.durationSecs ?: 0L) * 1000,
        )
    }

    private fun episodeMedia(core: HeyStreamCore, episodeId: Long, seriesId: Long): Media {
        val seriesName = core.item(Section.SERIES, seriesId)?.name?.takeIf { it.isNotBlank() }
        val info: SeriesInfo = try {
            SeriesInfoMemory.load(core, seriesId)
        } catch (failure: HeyStreamException) {
            // Hors ligne : l'épisode en cours est connu de « Continuer ».
            val resume = core.continueWatching(PlaybackKind.EPISODE, CONTINUE_LIMIT)
                .firstOrNull { it.resume.id == episodeId }?.resume
                ?: throw failure
            return Media(
                kind = PlaybackKind.EPISODE,
                id = episodeId,
                title = seriesName ?: resume.title,
                episode = null,
                image = resume.image,
                extension = resume.extension,
                durationMs = resume.durationMs.coerceAtLeast(0),
            )
        }
        val located = info.seasons.firstNotNullOfOrNull { season ->
            val index = season.episodes.indexOfFirst { it.id == episodeId }
            if (index < 0) null else Triple(season.number, index, season.episodes[index])
        } ?: throw HeyStreamException.NotFound()
        val (season, index, episode) = located
        return Media(
            kind = PlaybackKind.EPISODE,
            id = episodeId,
            title = seriesName ?: info.name.orEmpty(),
            episode = EpisodeLabel(season, episode.number ?: (index + 1).toLong(), episode.title?.takeIf { it.isNotBlank() }),
            image = info.cover ?: episode.image,
            extension = episode.extension,
            durationMs = (episode.durationSecs ?: 0L) * 1000,
        )
    }

    private fun onStatus(status: PlaybackStatus) {
        if (!started || closed) return
        // Le lecteur a repris la main (nouvelle tentative ou lecture).
        if (_state.value.retrying && (status.reconnectAttempt > 0 || !status.failed)) {
            _state.update { it.copy(retrying = false) }
        }
        // Pause (ou coupure) : position enregistrée.
        if (wasPlaying && !status.playing && !status.ended) saveProgress()
        wasPlaying = status.playing
        if (status.ended && !endHandled) {
            endHandled = true
            onEnded()
        }
    }

    /** Fin : position finale enregistrée (le cœur marque le titre vu), puis sortie ou épisode suivant. */
    private fun onEnded() {
        saveProgress(final = true)
        val media = media ?: return
        val seriesId = seriesId
        if (media.kind == PlaybackKind.MOVIE || seriesId == null) {
            _state.update { it.copy(exit = true) }
            return
        }
        viewModelScope.launch {
            val next = runCatching { session.withCore { it.nextEpisode(seriesId, media.id) } }.getOrNull()
            if (next == null || closed) {
                _state.update { it.copy(exit = true) }
                return@launch
            }
            startCountdown(next, seriesId)
        }
    }

    private fun startCountdown(next: Episode, seriesId: Long) {
        // Sans numéro du fournisseur : sa place dans la saison.
        val index = SeriesInfoMemory.get(seriesId)?.seasons
            ?.firstOrNull { it.number == next.season }?.episodes
            ?.indexOfFirst { it.id == next.id } ?: -1
        val label = EpisodeLabel(next.season, next.number ?: (index + 1).coerceAtLeast(1).toLong(), next.title?.takeIf { it.isNotBlank() })
        pendingNext = next
        nextJob = viewModelScope.launch {
            for (seconds in NEXT_EPISODE_SECONDS downTo 1) {
                _state.update { it.copy(next = NextEpisodeUi(label, seconds)) }
                delay(1_000)
            }
            playNextNow()
        }
    }

    /**
     * Enregistre la position (lue ici, sur le fil principal) ; l'écriture
     * se termine même si l'écran se ferme entre-temps.
     */
    private fun saveProgress(final: Boolean = false) {
        val media = media ?: return
        val player = player
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: media.durationMs
        val position = if (final && duration > 0) duration else player.currentPosition.coerceAtLeast(0)
        if (position <= 0 && !final) return
        val resume = Resume(
            kind = media.kind,
            id = media.id,
            positionMs = position,
            durationMs = duration.coerceAtLeast(0),
            title = media.title,
            image = media.image,
            extension = media.extension,
            seriesId = if (media.kind == PlaybackKind.EPISODE) seriesId else null,
        )
        ProgressWriter.save(session, resume)
    }
}

/**
 * Écritures de progression qui survivent à l'écran : la sortie du lecteur
 * enregistre la position même pendant la fermeture de l'écran.
 */
internal object ProgressWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun save(session: CoreSession, resume: Resume) {
        scope.launch {
            val saved = runCatching { session.withCore { it.saveProgress(resume) } }.isSuccess
            if (saved) session.notifyUserDataChanged()
        }
    }
}

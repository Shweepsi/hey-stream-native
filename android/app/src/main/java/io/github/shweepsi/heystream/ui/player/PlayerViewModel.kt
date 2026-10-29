package io.github.shweepsi.heystream.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
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
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.detail.SeriesInfoMemory
import io.github.shweepsi.heystream.ui.detail.visibleItem
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
    /**
     * Avance (positive) ou recul (négatif) cumulé par des appuis rapprochés,
     * pas encore appliqué ; `null` sans appui en cours.
     */
    val pendingSeekMs: Long? = null,
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

/** Pas d'un appui : on avance pour sauter un passage, on recule pour revoir une réplique. */
internal const val FORWARD_STEP_MS = PlayerController.SEEK_FORWARD_MS
internal const val BACK_STEP_MS = PlayerController.SEEK_BACK_MS

/** Délai sans nouvel appui avant d'appliquer le saut cumulé. */
internal const val SEEK_COMMIT_MS = 700L

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
 * Le titre à lire (film ou épisode, « Depuis le début ») est lu dans
 * [handle], celui de l'entrée de navigation ([PlayerArgs]) : il survit à
 * l'arrêt de l'app en arrière-plan.
 *
 * Utilisé depuis le fil principal (lecteur Media3).
 */
class PlayerViewModel(
    private val session: CoreSession,
    private val controller: PlayerController,
    handle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Titre en cours (et « Depuis le début »), gardé dans le handle de l'écran. */
    private val args = PlayerArgs(handle)
    private val seriesId: Long? = args.seriesId
    private var media: Media? = null

    /** Le flux de [media] a été donné au lecteur (son état le concerne). */
    private var started = false
    private var endHandled = false
    private var wasPlaying = false
    private var closed = false
    private var resolveJob: Job? = null
    private var nextJob: Job? = null

    /**
     * L'écran est au premier plan. En arrière-plan (TV éteinte, touche
     * Accueil), aucun flux ne s'ouvre : l'abonnement n'a souvent qu'une
     * connexion, et la lecture ne doit pas continuer seule.
     */
    private var foreground = true

    /** Flux préparé pendant que l'app était en arrière-plan : ouvert au retour. */
    private var deferred: Resolved? = null

    /** Épisode annoncé par le compte à rebours. */
    private var pendingNext: Episode? = null

    /** Reverrouillages du contrôle parental déjà pris en compte ([CoreSession.relocks]). */
    private var seenRelocks = session.relocks.value

    /** Vérification du contrôle parental avant la reprise ([checkHeld]). */
    private var holdJob: Job? = null

    private val player: Player get() = controller.player

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.update { it.copy(tracks = trackLists(tracks)) }
        }
    }

    init {
        player.addListener(listener)
        resolve()
        viewModelScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (started && !closed && controller.status.value.playing) saveProgress()
            }
        }
        viewModelScope.launch { controller.status.collect(::onStatus) }
        viewModelScope.launch { controller.held.collect { hold -> if (hold != 0L) checkHeld() } }
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

    /**
     * Un appui sur avance (+30 s) ou recul (−10 s) : les appuis rapprochés
     * s'additionnent (« +1:30 » affiché), et le saut part une seule fois,
     * [SEEK_COMMIT_MS] après le dernier — une seule requête au serveur.
     */
    fun nudge(forward: Boolean) {
        val step = if (forward) FORWARD_STEP_MS else -BACK_STEP_MS
        _state.update { it.copy(pendingSeekMs = (it.pendingSeekMs ?: 0L) + step) }
        pendingSeek?.cancel()
        pendingSeek = viewModelScope.launch {
            delay(SEEK_COMMIT_MS)
            commitSeek()
        }
    }

    /** Applique tout de suite le saut cumulé (fermeture de l'écran, autre commande). */
    private fun commitSeek() {
        pendingSeek?.cancel()
        pendingSeek = null
        val offset = _state.value.pendingSeekMs ?: return
        _state.update { it.copy(pendingSeekMs = null) }
        if (offset != 0L) seekBy(offset)
    }

    private var pendingSeek: Job? = null

    /** Écran fermé ou app quittée : le saut cumulé part maintenant (flux ouvert) ou est oublié. */
    private fun settleSeek() {
        if (started) commitSeek() else dropPendingSeek()
    }

    private fun dropPendingSeek() {
        pendingSeek?.cancel()
        pendingSeek = null
        if (_state.value.pendingSeekMs != null) _state.update { it.copy(pendingSeekMs = null) }
    }

    /** Avance ou recul, dans les limites du flux. */
    private fun seekBy(deltaMs: Long) {
        val player = player
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        player.seekTo(if (duration != null) target.coerceAtMost(duration - 1_000).coerceAtLeast(0) else target)
    }

    /** Barre de progression touchée ou glissée au doigt. */
    fun seekToFraction(fraction: Float) {
        // Position choisie sur la barre : les appuis en attente ne comptent plus.
        dropPendingSeek()
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

    /**
     * « Réessayer » : flux coupé (lecteur) ou adresse introuvable (fiche).
     * Flux pas encore ouvert : « Depuis le début » vaut toujours (handle).
     */
    fun retry() {
        if (_state.value.error != null || !started) {
            resolve()
        } else {
            _state.update { it.copy(retrying = true) }
            controller.retry()
        }
    }

    /** « Lire maintenant » ou fin du compte à rebours. */
    fun playNextNow() {
        val next = pendingNext ?: return
        nextJob?.cancel()
        nextJob = null
        pendingNext = null
        _state.update { it.copy(next = null) }
        // Gardé dans le handle : une restauration rouvre cet épisode, pas celui de la route.
        args.advanceTo(next.id)
        resolve()
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
        // Saut cumulé pas encore parti : appliqué, puis enregistré avec la position.
        settleSeek()
        closed = true
        resolveJob?.cancel()
        nextJob?.cancel()
        holdJob?.cancel()
        if (started && !endHandled) saveProgress()
        player.removeListener(listener)
        controller.stop()
    }

    /**
     * L'app passe en arrière-plan (le lecteur ferme alors le flux) : position
     * enregistrée, compte à rebours suspendu. Un titre fini ou pas encore
     * ouvert n'est pas rouvert par le lecteur au retour.
     */
    fun onBackground() {
        if (closed || !foreground) return
        settleSeek()
        foreground = false
        if (started && !endHandled) saveProgress()
        nextJob?.cancel()
        nextJob = null
        if (!started || endHandled) controller.stop()
    }

    /**
     * Retour au premier plan : flux préparé entre-temps ouvert, compte à
     * rebours relancé ; après la vérification du contrôle parental si elle
     * n'est pas finie ([checkHeld]).
     */
    fun onForeground() {
        if (closed || foreground) return
        foreground = true
        if (holdJob?.isActive != true) resumeScreen()
    }

    private fun resumeScreen() {
        deferred?.let {
            deferred = null
            open(it)
        }
        val next = pendingNext
        val seriesId = seriesId
        if (next != null && seriesId != null && nextJob == null) startCountdown(next, seriesId)
    }

    /**
     * L'app a quitté l'écran ([PlayerController.hold]) : une fois le
     * reverrouillage posé, si le titre est désormais caché par le contrôle
     * parental, rien n'est rouvert et l'écran se ferme ([lockOut]) ; sinon la
     * lecture reprend comme avant.
     */
    private fun checkHeld() {
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            session.awaitRelock()
            val relocks = session.relocks.value
            val hidden = relocks != seenRelocks && hiddenByLock()
            seenRelocks = relocks
            if (closed) return@launch
            if (hidden) lockOut()
            // Vérifié : la lecture reprend, ou plus rien n'est à reprendre (flux fermé).
            controller.releaseHold()
            if (!hidden && foreground) resumeScreen()
        }
    }

    /** Même règle qu'à l'ouverture ([visibleItem]) : le film, ou la série de l'épisode. */
    private suspend fun hiddenByLock(): Boolean {
        val movie = args.kind == PlaybackKind.MOVIE
        val id = if (movie) args.id else seriesId ?: return false
        return try {
            session.withCore { it.visibleItem(if (movie) Section.MOVIES else Section.SERIES, id) }
            false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: HeyStreamException.ParentalLock) {
            true
        } catch (_: Throwable) {
            // Cache illisible : rien ne dit que le titre est caché.
            false
        }
    }

    /**
     * Titre verrouillé pendant que l'app était quittée : le flux est fermé
     * sans être rouvert, plus rien du titre ne s'affiche et l'écran se ferme
     * (la fiche dessous montre le verrouillage). La position a été
     * enregistrée à la mise en arrière-plan.
     */
    private fun lockOut() {
        resolveJob?.cancel()
        nextJob?.cancel()
        nextJob = null
        pendingNext = null
        deferred = null
        started = false
        controller.stop()
        _state.value = PlayerUiState(exit = true)
    }

    override fun onCleared() {
        close()
    }

    // Lecture -------------------------------------------------------------------

    /** Prépare le titre du handle ([args]) : fiche, adresse, position de reprise. */
    private fun resolve() {
        resolveJob?.cancel()
        started = false
        endHandled = false
        wasPlaying = false
        deferred = null
        dropPendingSeek()
        _state.update { it.copy(resolving = true, error = null, retrying = false, next = null, tracks = TrackLists()) }
        val kind = args.kind
        val id = args.id
        // Position atteinte par l'écran (restauré), ou 0 pour « Depuis le début ».
        val startMs = args.startMs
        resolveJob = viewModelScope.launch {
            try {
                val resolved = session.withCore { core -> resolveMedia(core, kind, id, startMs) }
                if (closed) return@launch
                media = resolved.media
                _state.update {
                    it.copy(title = resolved.media.title, episode = resolved.media.episode, resolving = false, error = null)
                }
                if (foreground) open(resolved) else deferred = resolved
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                _state.update { it.copy(resolving = false, error = failure.toUiText()) }
            }
        }
    }

    private fun open(resolved: Resolved) {
        controller.playVod(resolved.url, resolved.startMs)
        started = true
    }

    /**
     * Appel bloquant (cœur) : fiche, extension, position de reprise, récents.
     * [startMs] : position imposée ([PlayerArgs.startMs]) ; `null` : reprise du cœur.
     */
    private fun resolveMedia(core: HeyStreamCore, kind: PlaybackKind, id: Long, startMs: Long?): Resolved {
        val media = when (kind) {
            PlaybackKind.MOVIE -> movieMedia(core, id)
            PlaybackKind.EPISODE -> episodeMedia(core, id, seriesId ?: throw HeyStreamException.NotFound())
        }
        val streamKind = if (kind == PlaybackKind.MOVIE) StreamKind.MOVIE else StreamKind.SERIES
        val url = core.streamUrl(streamKind, id, media.extension)
        val start = startMs ?: core.progress(kind, id) ?: 0L
        // Secondaire : un échec n'empêche pas la lecture.
        runCatching {
            if (kind == PlaybackKind.MOVIE) core.addRecent(Section.MOVIES, id) else seriesId?.let { core.addRecent(Section.SERIES, it) }
        }
        return Resolved(media, url, start.coerceAtLeast(0))
    }

    private fun movieMedia(core: HeyStreamCore, id: Long): Media {
        // Contenu peut-être verrouillé (écran restauré, session verrouillée) : pas de flux.
        val item = core.visibleItem(Section.MOVIES, id)
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
            title = Formats.displayName(item?.name?.takeIf { it.isNotBlank() } ?: info?.name.orEmpty()),
            episode = null,
            image = item?.image ?: info?.image,
            extension = item?.extension?.takeIf { it.isNotBlank() } ?: info?.extension,
            durationMs = (info?.durationSecs ?: 0L) * 1000,
        )
    }

    private fun episodeMedia(core: HeyStreamCore, episodeId: Long, seriesId: Long): Media {
        val seriesName = core.visibleItem(Section.SERIES, seriesId)?.name?.takeIf { it.isNotBlank() }?.let(Formats::displayName)
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
                title = seriesName ?: Formats.displayName(resume.title),
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
            title = seriesName ?: Formats.displayName(info.name.orEmpty()),
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
        _state.update { it.copy(next = NextEpisodeUi(label, NEXT_EPISODE_SECONDS)) }
        // En arrière-plan, le compte à rebours attend le retour (onForeground).
        if (!foreground) return
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
        // Gardée dans le handle : restauré après l'arrêt de l'app, l'écran
        // reprend là (même sous 30 s, que le cœur ne retient pas). Pas la
        // position finale : le titre est fini, le handle garde la dernière lue.
        if (!final && media.id == args.id) args.reached(position)
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

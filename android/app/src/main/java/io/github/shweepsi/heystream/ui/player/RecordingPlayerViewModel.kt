package io.github.shweepsi.heystream.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Recording
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.recording.RecordingRepository
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.toUiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lecture d'un enregistrement, comme un film (+30 s, −10 s, reprise, vu à
 * 95 %). Un enregistrement **en cours** se lit depuis son fichier qui grandit
 * (différé, sans seconde connexion) : les sauts rouvrent le fichier à
 * l'octet estimé d'après son débit moyen, jusqu'à quelques secondes du
 * direct. Quand la copie se termine, la lecture continue sur le fichier fini.
 *
 * Un enregistrement adulte ne s'ouvre que déverrouillé (même règle que les
 * listes, avec le code en vigueur).
 */
class RecordingPlayerViewModel(
    private val session: CoreSession,
    private val recordings: RecordingRepository,
    private val controller: PlayerController,
    private val id: Long,
) : ViewModel(), PlayerModel {
    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var recording: Recording? = null
    private var started = false
    private var closed = false
    private var foreground = true
    private var endHandled = false
    private var pendingSeek: Job? = null

    /** Fichier en cours d'écriture : début de la lecture dans l'enregistrement (ms). */
    private var growingOffsetMs = 0L
    private var growing = false

    private val player: Player get() = controller.player

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.update { it.copy(tracks = trackLists(tracks)) }
        }
    }

    init {
        player.addListener(listener)
        open()
        viewModelScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (started && !closed && controller.status.value.playing) savePosition()
            }
        }
        viewModelScope.launch {
            controller.status.collect { status ->
                if (started && !closed && status.ended && !endHandled) onEnded()
            }
        }
        viewModelScope.launch {
            // Copie terminée pendant la lecture : on passe au fichier fini, à la même place.
            recordings.active.drop(1).collect { active ->
                if (growing && active?.id != id && started && !closed) {
                    val position = displayedPositionMs()
                    growing = false
                    growingOffsetMs = 0
                    recording?.let { controller.playRecording(it.path, startMs = position) }
                }
            }
        }
    }

    private fun open() {
        _state.update { it.copy(resolving = true, error = null) }
        viewModelScope.launch {
            try {
                val found = recordings.withStore { it.recording(id) } ?: throw io.github.shweepsi.heystream.core.HeyStreamException.RecordingNotFound()
                if (found.isAdult && !session.withCore { it.isParentalUnlocked() }) {
                    _state.update { it.copy(resolving = false, error = UiText(R.string.error_parental_lock)) }
                    return@launch
                }
                if (found.volumeMissing || !File(found.path).exists()) {
                    _state.update { it.copy(resolving = false, error = UiText(R.string.error_volume_missing)) }
                    return@launch
                }
                recording = found
                _state.update { it.copy(title = recordingTitle(found), resolving = false) }
                if (foreground) play(found)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(resolving = false, error = error.toUiText()) }
            }
        }
    }

    private fun play(found: Recording) {
        started = true
        endHandled = false
        if (recordings.active.value?.id == found.id) {
            growing = true
            growingOffsetMs = 0
            controller.playRecording(found.path, growing = { recordings.active.value?.id == found.id })
        } else {
            growing = false
            // Reprise, sauf si l'enregistrement a été vu jusqu'au bout.
            val resume = found.positionMs.takeIf { !found.watched && found.durationMs > 0 && it < found.durationMs - END_MARGIN_MS } ?: 0L
            controller.playRecording(found.path, startMs = resume)
        }
    }

    // Commandes ---------------------------------------------------------------

    override fun togglePause() {
        val player = player
        if (player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)) player.pause() else player.play()
    }

    override fun nudge(forward: Boolean) {
        val step = if (forward) FORWARD_STEP_MS else -BACK_STEP_MS
        _state.update { it.copy(pendingSeekMs = (it.pendingSeekMs ?: 0L) + step) }
        pendingSeek?.cancel()
        pendingSeek = viewModelScope.launch {
            delay(SEEK_COMMIT_MS)
            commitSeek()
        }
    }

    private fun commitSeek() {
        pendingSeek?.cancel()
        pendingSeek = null
        val offset = _state.value.pendingSeekMs ?: return
        _state.update { it.copy(pendingSeekMs = null) }
        if (offset != 0L) seekTo(displayedPositionMs() + offset)
    }

    override fun seekToFraction(fraction: Float) {
        pendingSeek?.cancel()
        pendingSeek = null
        _state.update { it.copy(pendingSeekMs = null) }
        val duration = displayedDurationMs().takeIf { it > 0 } ?: return
        seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    private fun seekTo(targetMs: Long) {
        val found = recording ?: return
        if (!growing) {
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            player.seekTo(if (duration != null) targetMs.coerceIn(0, (duration - 1_000).coerceAtLeast(0)) else targetMs.coerceAtLeast(0))
            return
        }
        // Fichier qui grandit : rouvert à l'octet estimé (débit moyen depuis le début).
        val liveMs = displayedDurationMs()
        val target = targetMs.coerceIn(0, (liveMs - LIVE_DELAY_MS).coerceAtLeast(0))
        val size = File(found.path).length()
        val offsetBytes = if (liveMs > 0) (size.toDouble() * target / liveMs).toLong() else 0L
        growingOffsetMs = target
        controller.playRecording(found.path, growing = { recordings.active.value?.id == found.id }, startOffset = offsetBytes)
    }

    override fun selectTrack(key: TrackKey) {
        val player = player
        player.trackSelectionParameters = selectTrack(player.trackSelectionParameters, player.currentTracks, key)
    }

    override fun disableSubtitles() {
        val player = player
        player.trackSelectionParameters = disableSubtitles(player.trackSelectionParameters)
    }

    override fun retry() {
        val found = recording
        if (found == null || _state.value.error != null) open() else controller.retry()
    }

    override fun playNextNow() = Unit

    override fun cancelNext() = Unit

    override fun close() {
        if (closed) return
        closed = true
        pendingSeek?.cancel()
        if (started && !endHandled) savePosition()
        player.removeListener(listener)
        controller.stop()
    }

    override fun onBackground() {
        if (closed || !foreground) return
        foreground = false
        if (started && !endHandled) savePosition()
    }

    override fun onForeground() {
        if (closed || foreground) return
        foreground = true
        val found = recording
        if (found != null && !started) play(found)
    }

    override fun position(player: Player): PlaybackPosition =
        PlaybackPosition(positionMs = displayedPositionMs(), durationMs = displayedDurationMs())

    override fun onCleared() {
        close()
        super.onCleared()
    }

    /** Position dans l'enregistrement (fichier qui grandit : depuis l'octet de départ). */
    private fun displayedPositionMs(): Long = growingOffsetMs + player.currentPosition.coerceAtLeast(0)

    /** Durée : celle du fichier fini, ou le temps déjà enregistré. */
    private fun displayedDurationMs(): Long {
        if (!growing) return player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (recording?.durationMs ?: 0)
        val startedAt = recording?.startedAt ?: return 0
        return ((System.currentTimeMillis() / 1000 - startedAt) * 1000).coerceAtLeast(0)
    }

    private fun onEnded() {
        endHandled = true
        savePosition(final = true)
        _state.update { it.copy(exit = true) }
    }

    private fun savePosition(final: Boolean = false) {
        if (growing) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (final) duration else player.currentPosition.coerceAtLeast(0)
        saver.launch { runCatching { recordings.withStore { it.setPosition(id, position, duration) } } }
    }

    private companion object {
        const val SAVE_EVERY_MS = 10_000L

        /** Fichier en cours : jamais plus près du direct que ce délai. */
        const val LIVE_DELAY_MS = 5_000L

        /** À moins de 2 min de la fin, l'enregistrement repart du début. */
        const val END_MARGIN_MS = 120_000L

        /** Écritures de position qui survivent à l'écran. */
        val saver = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

/** Titre d'un enregistrement : son premier programme, sinon la chaîne. */
fun recordingTitle(recording: Recording): String = recording.programmes.firstOrNull()?.title ?: recording.channel

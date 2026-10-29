package io.github.shweepsi.heystream.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.shweepsi.heystream.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ce que le lecteur montre, pour l'interface. */
data class PlaybackStatus(
    /** Un flux est chargé (aperçu ou plein écran). */
    val active: Boolean = false,
    val live: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** Coupure : nouvelle tentative en cours (numéro de l'essai). */
    val reconnectAttempt: Int = 0,
    /** Lecture abandonnée après plusieurs essais. */
    val failed: Boolean = false,
    /** Fin d'un film ou d'un épisode. */
    val ended: Boolean = false,
)

/**
 * Le lecteur unique de l'app (Media3, décodage matériel). Les abonnements
 * n'acceptent souvent qu'une connexion : l'aperçu du direct et le plein
 * écran sont **le même lecteur**, et changer de flux ferme l'ancien avant
 * d'ouvrir le nouveau. Jamais deux flux ouverts, même un instant.
 *
 * À utiliser depuis le fil principal. Les URL ne sont jamais journalisées.
 */
@OptIn(UnstableApi::class)
class PlayerController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var current: MediaItem? = null

    /** Position de reprise du film en cours (après une coupure, au retour au premier plan). */
    private var resumeMs = 0L
    private var live = false
    private var attempt = 0
    private var resumeOnForeground = false
    private var pendingRetry: Runnable? = null

    /** App en arrière-plan : aucun flux ne s'ouvre avant son retour. */
    private var inBackground = false

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /**
     * Reprise retenue ([hold]) : numéro de la retenue en cours, 0 sans
     * retenue. L'écran du flux vérifie d'abord qu'il peut encore le montrer
     * (contrôle parental reverrouillé entre-temps), puis appelle
     * [releaseHold], ou [stop] puis [releaseHold] s'il est désormais caché.
     * Chaque retenue a son numéro : l'app quittée de nouveau avant la fin de
     * la vérification la relance, après le dernier reverrouillage.
     */
    private val _held = MutableStateFlow(0L)
    val held: StateFlow<Long> = _held.asStateFlow()
    private var holds = 0L
    private val isHeld get() = _held.value != 0L

    /** Le lecteur, créé au premier usage. */
    val player: ExoPlayer
        get() = exoPlayer ?: create().also { exoPlayer = it }

    /** Chaîne en direct (aperçu ou plein écran) ; reconnexion automatique. */
    fun playLive(url: String) = start(MediaItem.fromUri(url), live = true, startMs = 0)

    /** Film ou épisode, à partir de [startMs]. */
    fun playVod(url: String, startMs: Long) = start(MediaItem.fromUri(url), live = false, startMs = startMs)

    /**
     * Arrête et ferme le flux. Une retenue reste : un écran ouvert ensuite
     * (même titre relancé avant la fin du reverrouillage) vérifie d'abord.
     */
    fun stop() {
        cancelRetry()
        current = null
        resumeOnForeground = false
        exoPlayer?.run {
            stop()
            clearMediaItems()
        }
        _status.value = PlaybackStatus()
    }

    /** L'app passe en arrière-plan : le flux est fermé (connexion libérée). */
    fun onBackground() {
        inBackground = true
        val player = exoPlayer ?: return
        cancelRetry()
        // Flux pas encore rouvert (retenu) : sa reprise et sa position restent.
        if (!resumeOnForeground) {
            resumeOnForeground = current != null && player.playWhenReady
            if (!live && current != null) resumeMs = player.currentPosition
        }
        player.stop()
    }

    /**
     * Retour au premier plan : le flux reprend s'il jouait ; un film ou un
     * épisode en pause est rouvert en pause, à sa position (sinon « Lecture »
     * ne ferait plus rien sur un lecteur arrêté). Retenu ([hold]), il attend
     * [releaseHold].
     */
    fun onForeground() {
        inBackground = false
        if (!isHeld) resume()
    }

    /**
     * L'app quitte l'écran et le contrôle parental se reverrouille : ni ce
     * flux ni un flux demandé d'ici là ne s'ouvre avant [releaseHold], même
     * si l'app revient avant la fin du reverrouillage.
     */
    fun hold() {
        _held.value = ++holds
    }

    /** Le contenu reste visible : la reprise a lieu (aussitôt si l'app est déjà revenue). */
    fun releaseHold() {
        if (!isHeld) return
        _held.value = 0
        if (!inBackground) resume()
    }

    private fun resume() {
        val item = current ?: return
        when {
            resumeOnForeground -> {
                resumeOnForeground = false
                open(item, resumeMs)
            }
            !live && !_status.value.failed && exoPlayer?.playbackState == Player.STATE_IDLE ->
                open(item, resumeMs, playWhenReady = false)
        }
    }

    /** Nouvel essai après un échec définitif (bouton « Réessayer »), pas pendant une reprise retenue. */
    fun retry() {
        val item = current ?: return
        if (isHeld) return
        attempt = 0
        if (!live) resumeMs = exoPlayer?.currentPosition ?: resumeMs
        open(item, if (live) 0 else resumeMs)
    }

    fun release() {
        stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun start(item: MediaItem, live: Boolean, startMs: Long) {
        cancelRetry()
        this.live = live
        attempt = 0
        current = item
        resumeMs = startMs.coerceAtLeast(0)
        _status.value = PlaybackStatus(active = true, live = live, buffering = true)
        if (inBackground || isHeld) {
            // Chaîne ou titre choisi juste avant la mise en arrière-plan : le
            // flux s'ouvrira au retour, pas en cachette ; retenu, après la
            // vérification de son écran.
            exoPlayer?.stop()
            resumeOnForeground = true
            return
        }
        open(item, resumeMs)
    }

    private fun open(item: MediaItem, startMs: Long, playWhenReady: Boolean = true) {
        val player = player
        // L'ancien flux est fermé avant que le nouveau ne soit ouvert.
        player.stop()
        if (live) player.setMediaItem(item) else player.setMediaItem(item, startMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    private fun create(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("HeyStream/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            // Les panneaux redirigent souvent vers un serveur de flux (http ↔ https).
            .setAllowCrossProtocolRedirects(true)
        val extractors = DefaultExtractorsFactory()
            // Flux TS continus : démarrer sans attendre une image clé IDR.
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true)
        val renderers = DefaultRenderersFactory(context)
            // FFmpeg seulement pour l'audio que l'appareil ne décode pas (MP2, AC-3).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            // Démarrage rapide (zapping), tampon généreux ensuite (Wi-Fi).
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _status.update {
                it.copy(
                    playing = player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    attempt = 0
                    _status.update { it.copy(reconnectAttempt = 0, failed = false, ended = false) }
                }
                // Un flux en direct qui « se termine » est une coupure du serveur.
                Player.STATE_ENDED -> if (live) scheduleRetry() else _status.update { it.copy(ended = true) }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry()
        }
    }

    /** Reconnexion : 1, 2, 4, 8 s… (8 s au plus), puis abandon après [MAX_ATTEMPTS]. */
    private fun scheduleRetry() {
        val item = current ?: return
        cancelRetry()
        if (attempt >= MAX_ATTEMPTS) {
            _status.update { it.copy(failed = true, buffering = false, reconnectAttempt = 0) }
            return
        }
        attempt += 1
        _status.update { it.copy(reconnectAttempt = attempt, buffering = true) }
        if (!live) resumeMs = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        val retry = Runnable {
            pendingRetry = null
            // Un film en pause le reste (état lu maintenant : « Lecture » a pu
            // être appuyé entre-temps) ; le direct joue toujours.
            val play = live || (exoPlayer?.playWhenReady ?: true)
            if (current === item) open(item, if (live) 0 else resumeMs, playWhenReady = play)
        }
        pendingRetry = retry
        handler.postDelayed(retry, (1000L shl (attempt - 1)).coerceAtMost(8_000))
    }

    private fun cancelRetry() {
        pendingRetry?.let(handler::removeCallbacks)
        pendingRetry = null
    }

    companion object {
        /** Avance / recul rapides (spec : ±10 s). */
        const val SEEK_STEP_MS = 10_000L
        private const val MAX_ATTEMPTS = 6
    }
}

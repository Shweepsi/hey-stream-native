package io.github.shweepsi.heystream.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.core.RetryAction
import io.github.shweepsi.heystream.data.CrashReports
import io.github.shweepsi.heystream.data.Telemetry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ce que le lecteur montre, pour l'interface. */
data class PlaybackStatus(
    /** Un flux est chargé (aperçu ou plein écran). */
    val active: Boolean = false,
    /** Le flux est ouvert chez le fournisseur : il se charge ou se lit (pas arrêté, ni fini, ni en reconnexion). */
    val streaming: Boolean = false,
    val live: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** Coupure : nouvelle tentative en cours (numéro de l'essai). */
    val reconnectAttempt: Int = 0,
    /** Lecture abandonnée (plusieurs essais, ou une raison qu'un essai ne change pas). */
    val failed: Boolean = false,
    /** Raison de l'abandon, quand on sait la dire ; `null` : message général. */
    val problem: PlaybackProblem? = null,
    /** Aucun réseau pendant la reconnexion : `Pas de connexion Internet` plutôt que `Reconnexion…`. */
    val offline: Boolean = false,
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
class PlayerController(
    private val context: Context,
    /** Règle de relance : celle du cœur ; les tests donnent la leur (pas de bibliothèque native sous Robolectric). */
    private val retryPolicy: (status: Int?, attempt: Int) -> RetryAction = ::corePolicy,
    /** Plus aucun réseau ? Lu à chaque échec ; remplacé dans les tests. */
    private val offline: () -> Boolean = { isSystemOffline(context) },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var current: MediaItem? = null

    /** Position de reprise du film en cours (après une coupure, au retour au premier plan). */
    private var resumeMs = 0L
    private var live = false
    private var attempt = 0

    /** Abandon dû à une panne de connexion (sans réponse du fournisseur) : le retour du réseau relance. */
    private var networkFailure = false

    /** Retours au bord du direct dans la fenêtre en cours ([LIVE_EDGE_WINDOW_MS]), et l'heure du dernier. */
    private var liveEdgeJumps = 0
    private var lastLiveEdgeJump = 0L
    private var resumeOnForeground = false
    private var pendingRetry: Runnable? = null

    /** App en arrière-plan : aucun flux ne s'ouvre avant son retour. */
    private var inBackground = false

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Mesures d'ouverture dans le journal (essais sur appareil, voir [PerfProbe]). */
    private val perf = PerfProbe()

    /** Essai : le lecteur garde ses décodeurs entre deux flux (mode premier plan de Media3). */
    private var keepDecoders = false

    /** Décodeurs matériels qui ont planté : écartés jusqu'à la fin de l'app. */
    private val bannedDecoders: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        stats?.finish(exoPlayer)
        stats = null
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
        // En arrière-plan, les décodeurs sont rendus au système.
        if (keepDecoders) player.setForegroundMode(false)
    }

    /**
     * Retour au premier plan : le flux reprend s'il jouait ; un film ou un
     * épisode en pause est rouvert en pause, à sa position (sinon « Lecture »
     * ne ferait plus rien sur un lecteur arrêté). Retenu ([hold]), il attend
     * [releaseHold].
     */
    fun onForeground() {
        inBackground = false
        if (keepDecoders) exoPlayer?.setForegroundMode(true)
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
        liveEdgeJumps = 0
        if (!live) resumeMs = exoPlayer?.currentPosition ?: resumeMs
        open(item, if (live) 0 else resumeMs)
    }

    /**
     * Le réseau est revenu : une reconnexion qui attendait son délai part tout
     * de suite ; une lecture abandonnée faute de connexion repart d'elle-même.
     * Seulement après une panne de connexion ([networkFailure]), jamais après
     * un refus du fournisseur, un décodeur planté ou un flux qui se termine.
     */
    internal fun onNetworkBack() {
        val item = current ?: return
        // Un refus ou une panne du fournisseur n'a rien à voir avec le réseau : ses délais restent.
        if (inBackground || isHeld || !networkFailure) return
        val waiting = pendingRetry
        when {
            waiting != null -> {
                handler.removeCallbacks(waiting)
                waiting.run()
            }
            _status.value.failed -> {
                networkFailure = false
                attempt = 0
                liveEdgeJumps = 0
                // « Reconnexion… » plutôt que la carte d'échec, le temps que le flux reparte.
                _status.update { it.copy(failed = false, problem = null, offline = false, buffering = true, reconnectAttempt = 1) }
                if (!live) resumeMs = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: resumeMs
                val play = live || (exoPlayer?.playWhenReady ?: true)
                open(item, if (live) 0 else resumeMs, playWhenReady = play)
            }
        }
    }

    /** S'abonne au réseau par défaut (une fois) : le retour de la connexion relance la lecture. */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val manager = connectivity ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { onNetworkBack() }
            }
        }
        // Sans accès à l'état du réseau, la reconnexion garde ses délais.
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) networkCallback = callback
    }

    fun release() {
        stop()
        networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun start(item: MediaItem, live: Boolean, startMs: Long) {
        stats?.finish(exoPlayer)
        stats = if (Telemetry.active) PlaybackStats(item, live, startMs) else null
        cancelRetry()
        watchNetwork()
        // Le lecteur sert à tous les titres : un choix de pistes (sous-titres
        // coupés par la langue « Doublée », piste choisie à la main) ne
        // doit pas suivre au titre suivant.
        exoPlayer?.let { it.trackSelectionParameters = resetTracks(it.trackSelectionParameters) }
        this.live = live
        attempt = 0
        liveEdgeJumps = 0
        networkFailure = false
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
        perf.open()
        stats?.opening()
        val player = player
        // L'ancien flux est fermé avant que le nouveau ne soit ouvert.
        player.stop()
        if (live) player.setMediaItem(item) else player.setMediaItem(item, startMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    /** Pistes audio et sous-titres : celles par défaut du flux, aucun choix retenu. */
    private fun resetTracks(parameters: TrackSelectionParameters): TrackSelectionParameters =
        parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

    private val codecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        all.filter { it.name !in bannedDecoders }.ifEmpty { all }
    }

    private fun create(): ExoPlayer {
        // Variantes d'essai (journal Android), lues une fois à la création du lecteur.
        keepDecoders = PerfProbe.flag("HSExpFg")
        val asyncQueueing = PerfProbe.flag("HSExpAsync")
        val quickStart = PerfProbe.flag("HSExpBuf")
        if (Log.isLoggable(PerfProbe.TAG, Log.DEBUG)) {
            Log.d(PerfProbe.TAG, "variantes fg=$keepDecoders async=$asyncQueueing buf=$quickStart")
        }
        val http = DefaultHttpDataSource.Factory()
            .setTransferListener(perf.transfer)
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
            // Un décodeur qui a planté n'est plus proposé (le premier de la liste revient s'il ne reste rien).
            .setMediaCodecSelector(codecSelector)
            .also { if (asyncQueueing) it.forceEnableMediaCodecAsynchronousQueueing() }
        val loadControl = DefaultLoadControl.Builder()
            // Démarrage rapide (zapping), tampon généreux ensuite (Wi-Fi).
            .setBufferDurationsMs(15_000, 50_000, if (quickStart) 500 else 1_000, 2_500)
            // Plafond selon le tas de l'appareil (voir `BufferSize`).
            .setTargetBufferBytes(BufferSize.bytesFor(context))
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
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
            .also {
                it.addListener(listener)
                it.addAnalyticsListener(analytics)
                if (keepDecoders) it.setForegroundMode(true)
            }
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) stats?.seeking()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            stats?.playing(isPlaying)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            _status.update {
                it.copy(
                    playing = player.isPlaying,
                    buffering = player.playbackState == Player.STATE_BUFFERING,
                    streaming = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            stats?.state(playbackState)
            when (playbackState) {
                Player.STATE_READY -> {
                    perf.mark("ready")
                    attempt = 0
                    _status.update {
                        it.copy(reconnectAttempt = 0, failed = false, ended = false, problem = null, offline = false)
                    }
                }
                // Un flux en direct qui « se termine » est une coupure du serveur.
                Player.STATE_ENDED -> if (live) scheduleRetry(null) else _status.update { it.copy(ended = true) }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stats?.error(error, attempt, httpStatusOf(error))
            // Trop loin derrière le direct : retour à son bord, sans compter comme une panne.
            // Trois fois par fenêtre de 30 s : un flux qui y retombe sans cesse suit les relances.
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveEdgeJump > LIVE_EDGE_WINDOW_MS) liveEdgeJumps = 0
            if (live && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveEdgeJumps < MAX_LIVE_EDGE_JUMPS) {
                liveEdgeJumps += 1
                lastLiveEdgeJump = now
                exoPlayer?.run {
                    seekToDefaultPosition()
                    prepare()
                }
                return
            }
            // Décodeur planté en cours de route : écarté, le nouvel essai en prend un autre.
            failedDecoderOf(error)?.let { name -> if (bannedDecoders.add(name)) stats?.decoderDropped(name) }
            scheduleRetry(error)
        }
    }

    /** Mesures de la lecture en cours (APK testeur seulement, voir [Telemetry]). */
    private var stats: PlaybackStats? = null

    private val analytics = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            perf.mark("format_video ${format.width}x${format.height} ${format.sampleMimeType} reuse=${decoderReuseEvaluation?.result ?: -1}")
            stats?.format("video", format)
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            stats?.format("audio", format)
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            perf.mark("decoder_video ${initializationDurationMs}ms $decoderName")
            stats?.decoder("video", decoderName, initializationDurationMs)
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            perf.mark("first_frame")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            perf.mark("decoder_audio ${initializationDurationMs}ms $decoderName")
            stats?.decoder("audio", decoderName, initializationDurationMs)
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            stats?.let { it.dropped += droppedFrames }
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            stats?.let { it.bandwidth = bitrateEstimate }
        }
    }

    /**
     * Suite d'un échec ([error] : `null` pour un direct qui se termine). Le
     * cœur décide selon le code HTTP ([nextStep]) : reconnexion après 1, 2, 4,
     * 8 s…, ou abandon avec sa raison (abonnement utilisé ailleurs, compte
     * refusé, flux introuvable, format non pris en charge).
     */
    private fun scheduleRetry(error: PlaybackException?) {
        val item = current ?: return
        cancelRetry()
        val noNetwork = offline()
        networkFailure = isConnectivityFailure(error, noNetwork)
        val delayMs = when (val step = nextStep(error, attempt, noNetwork, retryPolicy)) {
            is Step.Retry -> step.delayMs
            is Step.Stop -> {
                // Seuls les codes partent : le message de l'erreur contient l'URL du flux.
                CrashReports.playbackFailed(error?.errorCodeName ?: "ENDED", live, httpStatusOf(error))
                _status.update {
                    it.copy(failed = true, buffering = false, reconnectAttempt = 0, problem = step.problem, offline = false)
                }
                return
            }
        }
        attempt += 1
        _status.update { it.copy(reconnectAttempt = attempt, buffering = true, offline = noNetwork) }
        if (!live) resumeMs = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        val retry = Runnable {
            pendingRetry = null
            // Un film en pause le reste (état lu maintenant : « Lecture » a pu
            // être appuyé entre-temps) ; le direct joue toujours.
            val play = live || (exoPlayer?.playWhenReady ?: true)
            if (current === item) open(item, if (live) 0 else resumeMs, playWhenReady = play)
        }
        pendingRetry = retry
        handler.postDelayed(retry, delayMs)
    }

    private fun cancelRetry() {
        pendingRetry?.let(handler::removeCallbacks)
        pendingRetry = null
    }

    companion object {
        /** Avance et recul rapides (spec : +30 s, −10 s, appuis cumulés). */
        const val SEEK_FORWARD_MS = 30_000L
        const val SEEK_BACK_MS = 10_000L
        private const val MAX_LIVE_EDGE_JUMPS = 3
        private const val LIVE_EDGE_WINDOW_MS = 30_000L
    }
}

/** Aucun réseau actif : le fournisseur ne peut pas répondre. */
private fun isSystemOffline(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return true
    return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == false
}

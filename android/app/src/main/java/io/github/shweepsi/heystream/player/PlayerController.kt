package io.github.shweepsi.heystream.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.data.CrashReports
import io.github.shweepsi.heystream.core.describeStreamUrl
import io.github.shweepsi.heystream.data.Telemetry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Débit mesuré d'une chaîne : octets reçus pendant [secs] secondes de flux. */
data class LiveRate(val streamId: Long, val bytes: Long, val secs: Double)

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
    /**
     * Flux refusé : un enregistrement a la seule connexion de l'abonnement
     * (la chaîne enregistrée se regarde depuis son fichier).
     */
    val blocked: Boolean = false,
    /** Direct lu depuis le différé (retour en arrière possible). */
    val timeshift: Boolean = false,
)

/** Position dans le différé, pour la barre de temps (secondes Unix du tampon). */
data class TimeshiftPosition(
    /** Moment affiché. */
    val at: Double,
    val oldest: Double,
    val newest: Double,
    val playing: Boolean,
    /** Temps avant que ce moment ne soit effacé du tampon (pause longue). */
    val eraseInSecs: Double? = null,
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

    /** Dernière erreur du flux en cours, pour le rapport si la lecture est abandonnée. */
    private var lastError: PlaybackException? = null
    private var resumeOnForeground = false
    private var pendingRetry: Runnable? = null

    /** App en arrière-plan : aucun flux ne s'ouvre avant son retour. */
    private var inBackground = false

    /** Fichier local (enregistrement) au lieu d'une URL du fournisseur. */
    private var localSource: DataSource.Factory? = null

    /** Différé lu (direct depuis le tampon), et ce qu'en attend l'écran du direct. */
    private var timeshift: LiveTimeshift? = null
    private var timeshiftReopen: (() -> Unit)? = null
    private var timeshiftLost: (() -> Unit)? = null

    /** Moment du tampon au début de la lecture en cours, et dernier fichier lu. */
    private var timeshiftStartAt = 0.0
    private val timeshiftSeq = java.util.concurrent.atomic.AtomicLong(-1)

    /** App passée en arrière-plan pendant le différé : l'écran le rouvrira au retour. */
    private var pendingTimeshiftReopen: (() -> Unit)? = null
    private var nextRunCheck: Runnable? = null

    /**
     * Un enregistrement est en cours : aucun flux du fournisseur ne s'ouvre
     * (une seule connexion). Tenu à jour par l'app.
     */
    var recordingActive: Boolean = false

    /** Octets reçus du fournisseur (fil de chargement de Media3). */
    private val received = java.util.concurrent.atomic.AtomicLong()

    /** Mesure en cours : octets reçus et position chargée au départ, et qui attend le résultat. */
    private var sampleBytes = -1L
    private var sampleMediaMs = 0L
    private var sampleStartedAt = 0L
    private var sampleDone: ((LiveRate?) -> Unit)? = null
    private val sampleTick = Runnable { sampleLiveRate() }

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
    fun playLive(url: String) = startRemote(MediaItem.fromUri(url), live = true, startMs = 0)

    /** Film ou épisode, à partir de [startMs]. */
    fun playVod(url: String, startMs: Long) = startRemote(MediaItem.fromUri(url), live = false, startMs = startMs)

    /**
     * Enregistrement : fichier fini (comme un film, à partir de [startMs]) ou
     * en cours d'écriture ([growing] non nul, lu à partir de l'octet
     * [startOffset], suivi tant qu'il grandit). Aucune connexion au
     * fournisseur.
     */
    fun playRecording(path: String, startMs: Long = 0, growing: (() -> Boolean)? = null, startOffset: Long = 0, live: Boolean = false) {
        val factory = if (growing != null) GrowingFileDataSource.Factory(growing, startOffset) else FileDataSource.Factory()
        dropTimeshift()
        start(MediaItem.fromUri(android.net.Uri.fromFile(File(path))), live = live, startMs = startMs, local = factory)
    }

    /**
     * Direct depuis le différé [session] : au direct ([at] nul) ou au moment
     * [at] du tampon. [reopen] rouvre la chaîne au retour au premier plan (le
     * tampon s'efface en arrière-plan) ; [lost] la passe en lecture réseau si
     * le tampon ne peut plus servir (disque débranché, réserve atteinte).
     */
    fun playTimeshift(session: LiveTimeshift, at: Double?, reopen: () -> Unit, lost: () -> Unit) {
        if (timeshift !== session) dropTimeshift()
        timeshift = session
        timeshiftReopen = reopen
        timeshiftLost = lost
        if (inBackground) {
            // Choisie juste avant l'arrière-plan : rien ne se copie en cachette.
            leaveTimeshiftForBackground()
            return
        }
        val spot = (if (at == null) session.liveSpot() else session.locate(at))
        if (spot == null) {
            lostTimeshift()
            return
        }
        openTimeshift(spot, playWhenReady = true, fresh = true)
    }

    /** Chaîne en cours d'ouverture (différé qui attend ses premières données). */
    fun opening() {
        stop()
        _status.value = PlaybackStatus(active = true, live = true, buffering = true)
    }

    /** Position dans le différé, `null` hors différé. */
    fun timeshiftPosition(): TimeshiftPosition? {
        val session = timeshift ?: return null
        val window = session.window() ?: return null
        val player = exoPlayer ?: return null
        val raw = timeshiftStartAt + player.currentPosition.coerceAtLeast(0) / 1000.0
        val at = raw.coerceIn(window.oldest, window.newest)
        // Moment déjà effacé (place manquante) : à reprendre tout de suite.
        val eraseIn = if (raw < window.oldest) 0.0 else session.eraseIn(at)
        return TimeshiftPosition(at, window.oldest, window.newest, player.playWhenReady, eraseIn)
    }

    /** Saut dans le différé, borné au plus ancien gardé et au direct. */
    fun seekTimeshift(at: Double) {
        val session = timeshift ?: return
        val window = session.window() ?: return
        val target = at.coerceIn(window.oldest, (window.newest - LiveTimeshift.LIVE_DELAY_SECS).coerceAtLeast(window.oldest))
        val spot = session.locate(target) ?: return
        openTimeshift(spot, playWhenReady = exoPlayer?.playWhenReady ?: true, fresh = false)
    }

    /** Pause ou lecture du différé. */
    fun toggleTimeshiftPause() {
        val player = exoPlayer ?: return
        if (timeshift == null) return
        player.playWhenReady = !player.playWhenReady
    }

    /** Pause longue : reprise au plus ancien moment encore gardé. */
    fun resumeTimeshiftFromOldest() {
        val session = timeshift ?: return
        val window = session.window() ?: return
        val raw = timeshiftStartAt + (exoPlayer?.currentPosition ?: 0).coerceAtLeast(0) / 1000.0
        if (raw >= window.oldest) {
            exoPlayer?.play()
            return
        }
        val spot = session.locate(window.oldest) ?: return
        openTimeshift(spot, playWhenReady = true, fresh = false)
    }

    /** Retour au direct. */
    fun timeshiftLive() {
        val spot = timeshift?.liveSpot() ?: return
        openTimeshift(spot, playWhenReady = true, fresh = false)
    }

    private fun openTimeshift(spot: io.github.shweepsi.heystream.core.TimeshiftSpot, playWhenReady: Boolean, fresh: Boolean) {
        val session = timeshift ?: return
        cancelNextRunCheck()
        timeshiftStartAt = spot.at
        timeshiftSeq.set(spot.seq.toLong())
        val factory = TimeshiftDataSource.Factory(session, spot.seq.toLong(), spot.offset.toLong()) { timeshiftSeq.set(it) }
        val item = MediaItem.fromUri(android.net.Uri.fromFile(File(spot.path)))
        if (fresh) {
            start(item, live = true, startMs = 0, local = factory, playWhenReady = playWhenReady)
        } else {
            // Saut : même lecture, autre point de départ (l'état affiché reste).
            localSource = factory
            current = item
            open(item, 0, playWhenReady)
        }
        _status.update { it.copy(timeshift = true) }
    }

    /** Fin d'une série (reconnexion du flux) : la suivante, dès qu'elle arrive. */
    private fun nextRun() {
        val session = timeshift ?: return
        cancelNextRunCheck()
        val last = timeshiftSeq.get()
        val lastRun = session.file(last)?.run
        var seq = last + 1
        // Déjà effacé (lecture restée en pause) : au plus ancien gardé.
        session.oldestSeq()?.takeIf { session.file(seq) == null && it > seq }?.let { seq = it }
        while (true) {
            val file = session.file(seq) ?: break
            if (file.run != lastRun) {
                // Début de la série suivante : son premier point d'index.
                val spot = session.locate(file.start) ?: break
                openTimeshift(spot, playWhenReady = exoPlayer?.playWhenReady ?: true, fresh = false)
                return
            }
            seq += 1
        }
        if (session.finished()) {
            lostTimeshift()
            return
        }
        // Reconnexion du cœur en cours : la suite arrivera.
        _status.update { it.copy(reconnectAttempt = 1, buffering = true) }
        val check = Runnable { nextRun() }
        nextRunCheck = check
        handler.postDelayed(check, NEXT_RUN_POLL_MS)
    }

    private fun cancelNextRunCheck() {
        nextRunCheck?.let(handler::removeCallbacks)
        nextRunCheck = null
    }

    /** Le tampon ne sert plus : l'écran passe en lecture réseau. */
    private fun lostTimeshift() {
        val lost = timeshiftLost
        dropTimeshift()
        exoPlayer?.stop()
        current = null
        localSource = null
        _status.value = PlaybackStatus(active = true, live = true, buffering = true)
        lost?.invoke()
    }

    /** Arrière-plan : le tampon s'efface (décision), la chaîne sera rouverte au retour. */
    private fun leaveTimeshiftForBackground() {
        pendingTimeshiftReopen = timeshiftReopen
        dropTimeshift()
        current = null
        localSource = null
        resumeOnForeground = false
    }

    /** Fin du différé lu : plus personne ne regarde (il s'arrête, sauf enregistrement). */
    private fun dropTimeshift() {
        cancelNextRunCheck()
        timeshift?.release()
        timeshift = null
        timeshiftReopen = null
        timeshiftLost = null
        timeshiftSeq.set(-1)
    }

    /**
     * Un enregistrement commence : le flux du fournisseur est fermé (sa
     * position gardée pour la reprise) et marqué refusé. Un fichier local
     * continue.
     */
    fun preemptForRecording() {
        recordingActive = true
        if (current == null || localSource != null) return
        cancelRetry()
        if (!live) resumeMs = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        resetSample()
        exoPlayer?.stop()
        resumeOnForeground = false
        _status.value = PlaybackStatus(active = true, live = live, blocked = true)
    }

    /** Position du flux en cours (ou retenue), pour enregistrer la reprise. */
    val positionMs: Long
        get() = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: resumeMs

    /**
     * Arrête et ferme le flux. Une retenue reste : un écran ouvert ensuite
     * (même titre relancé avant la fin du reverrouillage) vérifie d'abord.
     */
    fun stop() {
        stats?.finish(exoPlayer)
        stats = null
        resetSample()
        cancelRetry()
        dropTimeshift()
        pendingTimeshiftReopen = null
        current = null
        localSource = null
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
        if (timeshift != null) {
            player.stop()
            leaveTimeshiftForBackground()
            return
        }
        // Flux pas encore rouvert (retenu) : sa reprise et sa position restent.
        // Refusé (enregistrement en cours) : rien à rouvrir au retour.
        if (!resumeOnForeground && !_status.value.blocked) {
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
        pendingTimeshiftReopen?.let { reopen ->
            pendingTimeshiftReopen = null
            reopen()
            return
        }
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
        if (localSource == null && recordingActive) {
            _status.value = PlaybackStatus(active = true, live = live, blocked = true)
            return
        }
        attempt = 0
        if (timeshift != null) {
            retryTimeshift()
            return
        }
        if (!live) resumeMs = exoPlayer?.currentPosition ?: resumeMs
        open(item, if (live) 0 else resumeMs)
    }

    /** Erreur de lecture du tampon : reprise au même moment (ou au plus ancien gardé). */
    private fun retryTimeshift() {
        val session = timeshift ?: return
        val window = session.window()
        if (session.finished() || window == null) {
            lostTimeshift()
            return
        }
        val at = (timeshiftStartAt + (exoPlayer?.currentPosition ?: 0).coerceAtLeast(0) / 1000.0).coerceAtLeast(window.oldest)
        val spot = session.locate(at.coerceAtMost(window.newest)) ?: return lostTimeshift()
        openTimeshift(spot, playWhenReady = exoPlayer?.playWhenReady ?: true, fresh = false)
    }

    fun release() {
        stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startRemote(item: MediaItem, live: Boolean, startMs: Long) {
        dropTimeshift()
        if (recordingActive) {
            // Une seule connexion : celle de l'enregistrement. Rien ne s'ouvre.
            stats?.finish(exoPlayer)
            stats = null
            resetSample()
            cancelRetry()
            exoPlayer?.run {
                stop()
                clearMediaItems()
            }
            this.live = live
            current = item
            localSource = null
            resumeMs = startMs.coerceAtLeast(0)
            resumeOnForeground = false
            _status.value = PlaybackStatus(active = true, live = live, blocked = true)
            return
        }
        start(item, live, startMs, local = null)
    }

    private fun start(item: MediaItem, live: Boolean, startMs: Long, local: DataSource.Factory?, playWhenReady: Boolean = true) {
        stats?.finish(exoPlayer)
        stats = if (Telemetry.active && local == null) PlaybackStats(item, live, startMs) else null
        resetSample()
        cancelRetry()
        localSource = local
        this.live = live
        attempt = 0
        lastError = null
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
        open(item, resumeMs, playWhenReady)
    }

    private fun open(item: MediaItem, startMs: Long, playWhenReady: Boolean = true) {
        // Seul passage vers le fournisseur (lecture, reprise, nouvel essai) :
        // pendant un enregistrement, la connexion lui appartient.
        if (localSource == null && recordingActive) {
            exoPlayer?.stop()
            resumeOnForeground = false
            _status.value = PlaybackStatus(active = true, live = live, blocked = true)
            return
        }
        stats?.opening()
        // Flux (r)ouvert : une mesure du débit en cours est abandonnée.
        resetSample()
        val player = player
        // L'ancien flux est fermé avant que le nouveau ne soit ouvert.
        player.stop()
        val local = localSource
        when {
            local != null -> player.setMediaSource(
                ProgressiveMediaSource.Factory(local, extractors).createMediaSource(item),
                if (live) 0 else startMs,
            )
            live -> player.setMediaItem(item)
            else -> player.setMediaItem(item, startMs)
        }
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    private fun create(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setTransferListener(counter)
            .setUserAgent("HeyStream/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            // Les panneaux redirigent souvent vers un serveur de flux (http ↔ https).
            .setAllowCrossProtocolRedirects(true)
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
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
            .also {
                it.addListener(listener)
                it.addAnalyticsListener(analytics)
            }
    }

    private val extractors = DefaultExtractorsFactory()
        // Flux TS continus : démarrer sans attendre une image clé IDR.
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        .setConstantBitrateSeekingEnabled(true)

    /** Compte les octets reçus du fournisseur, pour la mesure du débit. */
    private val counter = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            received.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
    }

    /**
     * Mesure le débit de la chaîne en direct qui joue (dialogue
     * « Enregistrer ») : octets reçus rapportés au temps **de flux** chargé
     * (position chargée), pas au temps réel, sur [SAMPLE_MEDIA_MS] de flux.
     * [onDone] reçoit la mesure, ou `null` sans direct qui joue, si le flux
     * change ou si le délai est dépassé.
     */
    fun measureLiveRate(onDone: (LiveRate?) -> Unit) {
        finishSample(null)
        val player = exoPlayer
        if (player == null || current == null || !live || localSource != null || player.playbackState != Player.STATE_READY) {
            onDone(null)
            return
        }
        sampleBytes = received.get()
        sampleMediaMs = player.bufferedPosition
        sampleStartedAt = android.os.SystemClock.elapsedRealtime()
        sampleDone = onDone
        handler.postDelayed(sampleTick, SAMPLE_TICK_MS)
    }

    private fun sampleLiveRate() {
        val player = exoPlayer
        val item = current
        if (player == null || item == null || !live || localSource != null || sampleBytes < 0) {
            finishSample(null)
            return
        }
        val mediaMs = player.bufferedPosition - sampleMediaMs
        val bytes = received.get() - sampleBytes
        if (mediaMs >= SAMPLE_MEDIA_MS && bytes > 0) {
            val streamId = item.localConfiguration?.uri?.toString()?.let { describeStreamUrl(it) }?.streamId
            finishSample(streamId?.let { LiveRate(it, bytes, mediaMs / 1000.0) })
            return
        }
        if (mediaMs < 0 || android.os.SystemClock.elapsedRealtime() - sampleStartedAt > SAMPLE_TIMEOUT_MS) {
            // Flux reparti en arrière (discontinuité) ou qui ne charge plus : abandon.
            finishSample(null)
            return
        }
        handler.postDelayed(sampleTick, SAMPLE_TICK_MS)
    }

    /** Fin de la mesure en cours ([rate] nul : abandonnée). */
    private fun finishSample(rate: LiveRate?) {
        handler.removeCallbacks(sampleTick)
        sampleBytes = -1
        val done = sampleDone
        sampleDone = null
        done?.invoke(rate)
    }

    private fun resetSample() = finishSample(null)

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
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            stats?.state(playbackState)
            when (playbackState) {
                Player.STATE_READY -> {
                    attempt = 0
                    _status.update { it.copy(reconnectAttempt = 0, failed = false, ended = false) }
                }
                // Un flux en direct qui « se termine » est une coupure du serveur.
                Player.STATE_ENDED -> when {
                    // Différé : fin d'une série, la suivante s'ouvre.
                    timeshift != null -> nextRun()
                    live -> scheduleRetry()
                    else -> _status.update { it.copy(ended = true) }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stats?.error(error, attempt)
            lastError = error
            scheduleRetry()
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
            stats?.decoder("video", decoderName, initializationDurationMs)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
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

    /** Reconnexion : 1, 2, 4, 8 s… (8 s au plus), puis abandon après [MAX_ATTEMPTS]. */
    private fun scheduleRetry() {
        val item = current ?: return
        cancelRetry()
        if (attempt >= MAX_ATTEMPTS) {
            // Seul le code d'erreur part : son message contient l'URL du flux.
            CrashReports.playbackFailed(lastError?.errorCodeName ?: "ENDED", live)
            _status.update { it.copy(failed = true, buffering = false, reconnectAttempt = 0) }
            return
        }
        attempt += 1
        _status.update { it.copy(reconnectAttempt = attempt, buffering = true) }
        if (!live) resumeMs = exoPlayer?.currentPosition?.takeIf { it > 0 } ?: resumeMs
        if (timeshift != null) {
            // Différé : rien à redemander au fournisseur, le tampon est relu.
            val retry = Runnable {
                pendingRetry = null
                retryTimeshift()
            }
            pendingRetry = retry
            handler.postDelayed(retry, TIMESHIFT_RETRY_MS)
            return
        }
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
        /** Avance et recul rapides (spec : +30 s, −10 s, appuis cumulés). */
        const val SEEK_FORWARD_MS = 30_000L
        const val SEEK_BACK_MS = 10_000L
        private const val MAX_ATTEMPTS = 6

        /** Différé : nouvelle lecture du tampon après une erreur, et attente de la série suivante. */
        private const val TIMESHIFT_RETRY_MS = 500L
        private const val NEXT_RUN_POLL_MS = 500L

        /** Mesure du débit : 10 s de flux (le cœur ignore moins), 30 s au plus. */
        const val SAMPLE_MEDIA_MS = 10_000L
        private const val SAMPLE_TICK_MS = 1_000L
        private const val SAMPLE_TIMEOUT_MS = 30_000L
    }
}

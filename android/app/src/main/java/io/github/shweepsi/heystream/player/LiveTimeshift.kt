package io.github.shweepsi.heystream.player

import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.Timeshift
import io.github.shweepsi.heystream.core.TimeshiftFollower
import io.github.shweepsi.heystream.core.TimeshiftSpot
import io.github.shweepsi.heystream.core.TimeshiftWindow
import io.github.shweepsi.heystream.core.TuneEnd
import io.github.shweepsi.heystream.core.purgeTimeshift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Différé d'une chaîne (`docs/DECISIONS.md`, « Différé sur le direct ») : le
 * cœur copie le flux dans un tampon sur le disque des enregistrements, sur
 * un fil à part, et tient l'unique connexion ; le lecteur lit le tampon,
 * même au direct ([TimeshiftDataSource]).
 */
class LiveTimeshift(core: HeyStreamCore, private val timeshift: Timeshift) : TimeshiftFiles {
    val streamId: Long = timeshift.streamId()

    private val _end = MutableStateFlow<TuneEnd?>(null)

    /** Fin de la copie : [TuneEnd.STOPPED] quand plus personne ne regarde ni n'enregistre. */
    val end: StateFlow<TuneEnd?> = _end.asStateFlow()

    init {
        Thread({
            _end.value = try {
                core.runTimeshift(timeshift)
            } catch (_: Throwable) {
                // Abonnement fermé (déconnexion) : plus de copie possible.
                TuneEnd.CONNECTION_LOST
            }
        }, "timeshift").apply {
            isDaemon = true
            start()
        }
    }

    /** Quelqu'un regarde de nouveau (même chaîne, copie encore en vie). */
    fun view() = timeshift.setViewing(true)

    /**
     * Plus personne ne regarde : la copie s'arrête et le tampon s'efface,
     * sauf si un enregistrement la suit.
     */
    fun release() = timeshift.setViewing(false)

    /** Connexion reprise (autre chaîne à enregistrer) : tout s'arrête. */
    fun stop() = timeshift.stop()

    /** Attend la fin de la copie (connexion libérée), [timeoutMs] au plus. */
    suspend fun awaitEnd(timeoutMs: Long): TuneEnd? = withTimeoutOrNull(timeoutMs) { end.first { it != null } }

    /** La copie tourne encore (fin pas encore choisie par le cœur). */
    val alive: Boolean get() = _end.value == null && timeshift.end() == null

    fun window(): TimeshiftWindow? = timeshift.window()

    fun locate(at: Double): TimeshiftSpot? = timeshift.locate(at)

    /** Point de lecture du direct : quelques secondes avant le dernier reçu. */
    fun liveSpot(): TimeshiftSpot? {
        val window = window() ?: return null
        return locate((window.newest - LIVE_DELAY_SECS).coerceAtLeast(window.oldest))
    }

    fun bytes(): Long = timeshift.bytes().toLong()

    /** Débit mesuré (octets, secondes), s'il y a assez de données. */
    fun rate(): Pair<Long, Double>? = timeshift.rate()?.takeIf { it.secs >= MIN_RATE_SECS }?.let { it.bytes.toLong() to it.secs }

    /** Enregistrement de cette chaîne qui suivra le tampon depuis [from]. */
    fun attach(from: Long): TimeshiftFollower? = timeshift.attach(from)

    override fun file(seq: Long): TimeshiftFile? =
        timeshift.segment(seq.toULong())?.let { TimeshiftFile(it.seq.toLong(), it.run.toLong(), it.path, it.complete, it.start, it.end) }

    override fun oldestSeq(): Long? = window()?.let { locate(it.oldest)?.seq?.toLong() }

    override fun finished(): Boolean = timeshift.end() != null

    /**
     * Temps avant que le moment [at] ne soit effacé par la profondeur du
     * tampon (pause longue) : son fichier part une heure après sa fin.
     */
    fun eraseIn(at: Double): Double? {
        val window = window() ?: return null
        val spot = locate(at) ?: return null
        val file = file(spot.seq.toLong()) ?: return 0.0
        return file.end + DEPTH_SECS - window.newest
    }

    companion object {
        /** Profondeur du tampon (comme le cœur). */
        const val DEPTH_SECS = 3600.0

        /** Le direct : jamais plus près que ce retard (le lecteur garde un peu d'avance). */
        const val LIVE_DELAY_SECS = 2.0

        /** Débit retenu pour l'estimation au-delà de ce temps de données. */
        const val MIN_RATE_SECS = 10.0
    }
}

/**
 * Le différé en cours (un seul : une seule connexion), partagé par le direct
 * et le service d'enregistrement. Ouverture, arrêt et nettoyage passent par
 * un même verrou : jamais deux copies, même pendant un changement de chaîne
 * qui croise le début d'un enregistrement.
 */
class TimeshiftHolder {
    private val _current = MutableStateFlow<LiveTimeshift?>(null)
    val current: StateFlow<LiveTimeshift?> = _current.asStateFlow()
    private val mutex = Mutex()

    /**
     * Nouveau différé de [streamId], `null` si la connexion n'est pas libre :
     * le précédent est relâché et doit avoir fermé sa connexion, et [allowed]
     * (aucun enregistrement) est revérifié juste avant l'ouverture.
     */
    suspend fun start(core: HeyStreamCore, streamId: Long, dir: String, allowed: () -> Boolean): LiveTimeshift? = mutex.withLock {
        _current.value?.let { old ->
            old.release()
            old.awaitEnd(CLOSE_TIMEOUT_MS)
            // Gardé par un enregistrement (ou qui ne se ferme pas) : pas de seconde connexion.
            if (old.alive) return@withLock null
        }
        if (!allowed()) return@withLock null
        val opened = withContext(Dispatchers.IO) { core.openTimeshift(streamId, dir) }
        LiveTimeshift(core, opened).also { _current.value = it }
    }

    /** Différé encore en vie de [streamId] (un enregistrement le gardait) : repris. */
    fun reuse(streamId: Long): LiveTimeshift? =
        _current.value?.takeIf { it.streamId == streamId && it.alive }?.also { it.view() }

    /** Plus personne ne regarde. */
    fun release() {
        _current.value?.release()
    }

    /**
     * La connexion va à un enregistrement qui ne suit pas le différé : il
     * s'arrête, et on attend sa fermeture. Une ouverture en cours passe
     * d'abord (même verrou), puis s'arrête aussi.
     */
    suspend fun stop() = mutex.withLock {
        val current = _current.value ?: return@withLock
        current.stop()
        current.awaitEnd(CLOSE_TIMEOUT_MS)
    }

    /** Au lancement, sans différé : efface les tampons d'un arrêt brutal dans [dir]. */
    suspend fun purge(dir: String) = mutex.withLock {
        if (_current.value?.alive == true) return@withLock
        withContext(Dispatchers.IO) { purgeTimeshift(dir) }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}

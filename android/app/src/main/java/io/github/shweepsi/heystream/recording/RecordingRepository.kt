package io.github.shweepsi.heystream.recording

import android.content.Context
import io.github.shweepsi.heystream.core.RecordingControl
import io.github.shweepsi.heystream.core.RecordingStore
import io.github.shweepsi.heystream.data.CoreSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Copie en cours (service d'enregistrement) : la seule connexion lui appartient. */
data class ActiveRecording(
    val id: Long,
    val streamId: Long,
    /** Fichier en cours d'écriture, lisible pendant l'enregistrement. */
    val path: String,
    val control: RecordingControl,
    /**
     * Suit le différé de sa chaîne (même connexion) : la chaîne se regarde
     * depuis le différé, pas depuis le fichier.
     */
    val following: Boolean = false,
)

/**
 * Enregistrement sur le point de commencer pendant une lecture : compte à
 * rebours d'une minute, puis la lecture s'arrête (sa reprise est gardée).
 */
data class RecordingCountdown(val id: Long, val title: String, val adult: Boolean, val startsAt: Long)

/**
 * Planning et liste des enregistrements (cœur : `RecordingStore`, base à
 * part du catalogue, gardée après un changement d'identifiant). Méthodes
 * bloquantes appelées hors du fil principal par [withStore].
 */
class RecordingRepository(
    private val context: Context,
    private val open: () -> RecordingStore = {
        CoreSession.loadNativeLibrary()
        RecordingStore(context.filesDir.absolutePath)
    },
) {
    private val store: RecordingStore by lazy { open() }

    /** Change quand la liste change : les écrans qui la montrent la relisent. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _active = MutableStateFlow<ActiveRecording?>(null)
    val active: StateFlow<ActiveRecording?> = _active.asStateFlow()

    private val _countdown = MutableStateFlow<RecordingCountdown?>(null)
    val countdown: StateFlow<RecordingCountdown?> = _countdown.asStateFlow()

    /** Notification « Regarder » : enregistrement à ouvrir. */
    private val _openRequests = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val openRequests: SharedFlow<Long> = _openRequests.asSharedFlow()

    suspend fun <T> withStore(block: (RecordingStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    /** Accès direct (service, déjà hors du fil principal). */
    fun storeBlocking(): RecordingStore = store

    /**
     * Disque d'enregistrement : celui choisi, sinon le premier volume qui
     * accepte un dossier privé à l'app (mémoire interne), retenu.
     */
    suspend fun ensureDirectory(): String? = withStore { store ->
        store.directory() ?: RecordingVolumes.list(context).firstOrNull()?.path?.also { store.setDirectory(it) }
    }

    /** Liste ou planning changé : écrans relus, alarme reprogrammée. */
    fun changed() {
        _version.update { it + 1 }
        RecordingScheduler.update(context)
    }

    fun setActive(active: ActiveRecording?) {
        _active.value = active
        _version.update { it + 1 }
    }

    fun setCountdown(countdown: RecordingCountdown?) {
        _countdown.value = countdown
    }

    fun requestOpen(id: Long) {
        _openRequests.tryEmit(id)
    }

    /** La demande d'ouverture a été suivie : pas rejouée à la prochaine collecte. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeOpenRequest() {
        _openRequests.resetReplayCache()
    }
}

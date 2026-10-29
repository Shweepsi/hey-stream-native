package io.github.shweepsi.heystream.data

import android.content.Context
import io.github.shweepsi.heystream.core.AccountInfo
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.SectionRefresh
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** État de la session : l'app s'ouvre sur l'accueil (cache) ou sur la connexion. */
sealed interface SessionState {
    data object Starting : SessionState

    data object SignedOut : SessionState

    data object Ready : SessionState
}

/** Rafraîchissement du catalogue en cours ou terminé. */
sealed interface RefreshState {
    data object Idle : RefreshState

    data object Running : RefreshState

    data class Done(val sections: List<SectionRefresh>) : RefreshState

    data class Failed(val error: Throwable) : RefreshState
}

/**
 * Le cœur de l'abonnement, unique dans l'app. Ses méthodes sont bloquantes :
 * [withCore] les appelle hors du fil principal.
 */
class CoreSession(
    context: Context,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    /** Ouvre le cœur d'un abonnement (un faux cœur dans les tests). */
    private val open: (Credentials) -> HeyStreamCore = { saved -> openNative(saved, context.filesDir.absolutePath) },
) {
    private val lock = Mutex()

    @Volatile
    private var core: HeyStreamCore? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _refresh = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refresh: StateFlow<RefreshState> = _refresh.asStateFlow()

    /** Infos de la dernière authentification réussie (accueil), connues hors ligne. */
    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    /** Change quand le catalogue en cache change : les listes se rechargent. */
    private val _catalogVersion = MutableStateFlow(0L)
    val catalogVersion: StateFlow<Long> = _catalogVersion.asStateFlow()

    /**
     * Change quand les données de l'utilisateur changent (favoris, vus,
     * reprise, catégories masquées, déverrouillage parental) : les listes
     * qui les montrent se rechargent.
     */
    private val _userDataVersion = MutableStateFlow(0L)
    val userDataVersion: StateFlow<Long> = _userDataVersion.asStateFlow()

    /**
     * Ouvre la session dans la portée de l'app : une activité recréée
     * (changement de langue) pendant l'ouverture ne l'interrompt pas.
     */
    fun ensureStarted() {
        scope.launch { start() }
    }

    /** Ouvre le cache de l'abonnement enregistré, sans requête réseau. */
    suspend fun start() {
        lock.withLock {
            if (_state.value != SessionState.Starting) return
            val saved = withContext(Dispatchers.IO) { credentials.load() }
            if (saved == null) {
                _state.value = SessionState.SignedOut
                return
            }
            core = try {
                withContext(Dispatchers.IO) { open(saved) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            _state.value = if (core != null) SessionState.Ready else SessionState.SignedOut
        }
        if (core != null) {
            _account.value = runCatching { withCore { it.accountInfo() } }.getOrNull()
            // Infos de l'abonnement et catalogue de plus de 24 h, en arrière-plan.
            scope.launch { runCatching { _account.value = withCore { it.authenticate() } } }
            refreshInBackground(force = false)
        }
    }

    /**
     * Formulaire de connexion : vérifie les identifiants avant de les
     * enregistrer. En cas d'échec, rien n'est enregistré ni effacé.
     *
     * Ne s'interrompt pas si l'écran qui l'a demandée disparaît (retour
     * pendant « Vérification… ») : une fois la vérification réussie, le cœur
     * a déjà lié le cache au nouvel abonnement, et la session doit le suivre
     * (infos, listes, rechargement du catalogue).
     */
    suspend fun signIn(server: String, username: String, password: String): AccountInfo = withContext(NonCancellable) {
        val result = lock.withLock {
            withContext(Dispatchers.IO) {
                val candidate = Credentials(server.trim(), username.trim(), password)
                val opened = open(candidate)
                try {
                    val account = opened.authenticate()
                    credentials.save(candidate)
                    core?.close()
                    core = opened
                    account
                } catch (error: Throwable) {
                    opened.close()
                    throw error
                }
            }
        }
        _account.value = result
        _state.value = SessionState.Ready
        _catalogVersion.update { it + 1 }
        _userDataVersion.update { it + 1 }
        refreshInBackground(force = false)
        result
    }

    /** Déconnexion : efface identifiants, catalogue et données de l'abonnement. */
    suspend fun signOut() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                core?.let { current ->
                    runCatching { current.signOut() }
                    current.close()
                }
                core = null
                credentials.clear()
            }
            _refresh.value = RefreshState.Idle
            _account.value = null
            _state.value = SessionState.SignedOut
        }
    }

    /**
     * Appelle le cœur hors du fil principal. Lève [HeyStreamException] (dont
     * `SignedOut` sans session).
     */
    suspend fun <T> withCore(block: (HeyStreamCore) -> T): T = withContext(Dispatchers.IO) {
        block(core ?: throw HeyStreamException.SignedOut())
    }

    /** Identifiants enregistrés, pour préremplir « Modifier l'adresse du serveur ». Jamais journalisés. */
    suspend fun savedCredentials(): Credentials? = withContext(Dispatchers.IO) { credentials.load() }

    /** Dernier reverrouillage lancé (fil principal) ; chacun attend le précédent. */
    private var relock: Job? = null

    /**
     * Nombre de reverrouillages effectifs (le cœur était déverrouillé) : un
     * écran qui montre un contenu le revérifie, même caché sous un autre.
     */
    private val _relocks = MutableStateFlow(0L)
    val relocks: StateFlow<Long> = _relocks.asStateFlow()

    /**
     * Quitter l'app reverrouille les catégories adultes (spec : le
     * déverrouillage vaut pour la session).
     */
    fun lockParental() {
        val previous = relock
        relock = scope.launch {
            // Les uns après les autres : attendre le dernier suffit (awaitRelock).
            previous?.join()
            val wasUnlocked = runCatching {
                withCore { core -> core.isParentalUnlocked().also { if (it) core.lockParental() } }
            }.getOrDefault(false)
            if (wasUnlocked) {
                _relocks.update { it + 1 }
                notifyUserDataChanged()
            }
        }
    }

    /**
     * Attend la fin des reverrouillages lancés ([lockParental]) : ce qui est
     * lu ensuite dans le cœur tient compte du verrou.
     */
    suspend fun awaitRelock() {
        relock?.join()
    }

    /** À appeler après un changement de favoris, vus, reprise, masquage ou code PIN. */
    fun notifyUserDataChanged() {
        _userDataVersion.update { it + 1 }
    }

    /** Rafraîchit le catalogue (sections de plus de 24 h, toutes si [force]). */
    fun refreshInBackground(force: Boolean) {
        scope.launch { runCatching { refreshNow(force) } }
    }

    /** Rafraîchissement attendu (bouton manuel, tâche quotidienne). */
    suspend fun refreshNow(force: Boolean): List<SectionRefresh> {
        // Cœur remplacé pendant le téléchargement (« Modifier l'adresse du
        // serveur ») : son état ne concerne plus la session, et ne doit pas
        // masquer celui du nouveau.
        val used = core
        _refresh.value = RefreshState.Running
        return try {
            val sections = withContext(Dispatchers.IO) { (used ?: throw HeyStreamException.SignedOut()).refresh(force) }
            // Le cache, lui, est partagé : ce que le cœur a pu y enregistrer
            // (même abonnement, ou avant d'être remplacé) recharge les listes.
            if (sections.any { it.refreshed }) _catalogVersion.update { it + 1 }
            if (core === used) _refresh.value = RefreshState.Done(sections)
            sections
        } catch (error: Throwable) {
            if (core === used) _refresh.value = RefreshState.Failed(error)
            throw error
        }
    }

    private companion object {
        fun openNative(saved: Credentials, dataDir: String): HeyStreamCore {
            loadNativeLibrary()
            return HeyStreamCore(saved.server, saved.username, saved.password, dataDir)
        }

        @Volatile
        private var loaded = false

        /**
         * Charge `libheystream.so` par le chargeur d'Android avant JNA : la
         * bibliothèque reste dans l'APK (non extraite) et JNA la retrouve
         * ensuite par son nom.
         */
        fun loadNativeLibrary() {
            if (!loaded) {
                System.loadLibrary("heystream")
                loaded = true
            }
        }
    }
}

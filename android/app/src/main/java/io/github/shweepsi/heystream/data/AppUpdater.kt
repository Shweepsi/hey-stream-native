package io.github.shweepsi.heystream.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.HeyStreamApp
import io.github.shweepsi.heystream.core.AppUpdate
import io.github.shweepsi.heystream.core.UpdateProgress
import io.github.shweepsi.heystream.core.checkAppUpdate
import io.github.shweepsi.heystream.core.checkTesterUpdate
import io.github.shweepsi.heystream.core.downloadAppUpdate
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Étape d'une mise à jour qui a échoué. */
enum class UpdateStep { Check, Download, Install }

/** Mise à jour de l'app, de la vérification à l'installation. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val update: AppUpdate) : UpdateState

    /** [progress] de 0 à 1, `null` si la taille est inconnue. */
    data class Downloading(val update: AppUpdate, val progress: Float?) : UpdateState

    /**
     * APK confié à l'installateur d'Android, qui remplace l'app (après sa
     * confirmation, s'il la demande). Le bouton rouvre la fenêtre de confirmation de **la même
     * session** (quittée par Accueil, par exemple), jamais une nouvelle session. Au retour à l'app,
     * si Android n'a plus la session, « Installer » redevient possible ([AppUpdater.onForeground]).
     */
    data class Installing(val update: AppUpdate) : UpdateState

    /**
     * Android doit d'abord autoriser l'app à installer des applications.
     * [denied] : l'utilisateur est revenu de l'écran d'Android sans que
     * l'autorisation soit effective (box à profil professionnel : l'écran règle
     * la copie du profil, pas celle de l'app).
     */
    data class NeedsPermission(val update: AppUpdate, val denied: Boolean = false) : UpdateState

    /** Installation reprise toute seule après l'autorisation (qui ferme l'app) : version à retrouver puis installer. */
    data object Resuming : UpdateState

    /** Confirmation d'Android refusée ou laissée de côté : « Installer » reprend avec l'APK déjà téléchargé. */
    data class Aborted(val update: AppUpdate) : UpdateState

    data class Failed(val step: UpdateStep, val update: AppUpdate?) : UpdateState
}

/**
 * Mise à jour depuis les releases GitHub du projet. Le cœur trouve la
 * version la plus récente, télécharge et vérifie l'APK (taille, SHA-256) ;
 * ici, on le confie à l'installateur d'Android.
 * L'APK étant signé par la même clé, Android le pose par-dessus l'app sans
 * rien effacer (abonnement, favoris, reprise). Dès que l'app a elle-même
 * installé la version en place (Android 12+), Android ne redemande plus de
 * confirmation : « Installer » suffit.
 *
 * À chaque retour au premier plan (APK release seulement), une vérification
 * par jour au plus, sauf si elle est coupée dans les réglages
 * ([autoCheck]) : une version remise à plus tard est reproposée le lendemain.
 * La recherche à la main reste toujours possible.
 *
 * L'autorisation « applis inconnues » ferme souvent l'app (Android : « REQUEST_INSTALL_PACKAGES
 * changed ») : la version voulue est donc retenue dans les préférences avant d'ouvrir l'écran
 * d'Android, et l'installation reprend d'elle-même à la réouverture ([resumePending]).
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Version proposée d'elle-même au lancement, dans une boîte. */
    private val _prompt = MutableStateFlow<AppUpdate?>(null)
    val prompt: StateFlow<AppUpdate?> = _prompt.asStateFlow()

    /** Recherche automatique au lancement (réglage de À propos, activée par défaut). */
    private val _autoCheck = MutableStateFlow(prefs().getBoolean(KEY_AUTO, true))
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    private var job: Job? = null

    /** Session d'installation confiée à Android (`NO_SESSION` sinon) : seules ses réponses comptent. */
    @Volatile
    private var installSession = NO_SESSION

    /** Fenêtre de confirmation d'Android pour cette session, rouvrable si l'utilisateur l'a quittée. */
    @Volatile
    private var confirmation: Intent? = null

    /** APK déjà téléchargé et vérifié, pour un nouvel essai d'installation. */
    private var downloaded: Pair<String, File>? = null

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * À chaque retour au premier plan : reprend une installation qui
     * attendait l'autorisation d'Android, et vérifie s'il y a une mise à jour
     * une fois par jour au plus.
     */
    fun onForeground() {
        when (val state = _state.value) {
            is UpdateState.NeedsPermission ->
                if (context.packageManager.canRequestPackageInstalls()) {
                    install(state.update)
                } else {
                    // Retour de l'écran d'Android sans autorisation effective : l'aide le dit.
                    _state.value = state.copy(denied = true)
                }
            // Retour à l'app : si Android n'a plus la session (abandonnée, terminée sans réponse reçue),
            // « Installer » redevient possible. Tant qu'elle existe, une installation en cours n'est pas
            // dite annulée, et le bouton rouvre sa confirmation.
            is UpdateState.Installing -> if (!installSessionExists()) _state.value = UpdateState.Aborted(state.update)
            else -> if (resumePending()) return
        }
        checkIfDue()
    }

    /** Android connaît encore la session confiée (ou elle est en cours de création). */
    private fun installSessionExists(): Boolean {
        val id = installSession
        if (id == NO_SESSION) return true
        return runCatching { context.packageManager.packageInstaller.getSessionInfo(id) != null }.getOrDefault(true)
    }

    /** Active ou coupe la recherche automatique ; le bouton des réglages cherche toujours. */
    fun setAutoCheck(enabled: Boolean) {
        prefs().edit { putBoolean(KEY_AUTO, enabled) }
        _autoCheck.value = enabled
    }

    /** Jamais en debug : ni les tests ni l'émulateur n'interrogent GitHub. */
    private fun checkIfDue() {
        if (BuildConfig.DEBUG || !_autoCheck.value) return
        // Une installation en cours ou en attente de l'utilisateur ne doit pas être remplacée par « Recherche… ».
        if (_state.value.installFlow()) return
        val elapsed = System.currentTimeMillis() - prefs().getLong(KEY_CHECKED_AT, 0)
        if (elapsed in 0 until CHECK_INTERVAL_MS) return
        check(automatic = true)
    }

    /** Recherche une mise à jour (bouton des réglages, ou [checkIfDue]). */
    fun check(automatic: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val update = fetchUpdate()
                prefs().edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }
                if (update == null) {
                    UpdateState.UpToDate
                } else {
                    if (automatic) _prompt.value = update
                    UpdateState.Available(update)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Au lancement, un échec (hors ligne) ne dérange personne.
                if (automatic) UpdateState.Idle else UpdateState.Failed(UpdateStep.Check, null)
            }
        }
    }

    /** Version publiée plus récente que celle installée, ou `null`. */
    private suspend fun fetchUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        CoreSession.loadNativeLibrary()
        if (BuildConfig.TESTER) {
            // APK testeur : jamais sur GitHub, servi par le service de collecte.
            if (!Telemetry.available) {
                null
            } else {
                checkTesterUpdate(BuildConfig.TELEMETRY_URL, BuildConfig.TELEMETRY_KEY, BuildConfig.VERSION_NAME, Telemetry.device)
            }
        } else {
            checkAppUpdate(BuildConfig.VERSION_NAME)
        }
    }

    /** « Plus tard » : la boîte se ferme, la version sera reproposée le lendemain. */
    fun dismiss() {
        _prompt.value = null
        // « Plus tard » annule aussi la reprise voulue : l'autorisation donnée ensuite n'installe rien.
        clearPending()
        val state = _state.value
        if (state is UpdateState.Failed || state is UpdateState.NeedsPermission || state is UpdateState.Installing || state is UpdateState.Aborted) {
            _state.value = state.pendingUpdate()?.let { UpdateState.Available(it) } ?: UpdateState.Idle
        }
    }

    /** Télécharge (sauf si c'est déjà fait) puis installe [update]. */
    fun install(update: AppUpdate) {
        // Confirmation d'Android en cours : on rouvre sa fenêtre, jamais une deuxième session.
        if (_state.value is UpdateState.Installing) {
            reopenConfirmation()
            return
        }
        // Pendant le téléchargement ou la reprise, un deuxième appui n'ouvre rien.
        if (job?.isActive == true || _state.value is UpdateState.Resuming) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            // Android ferme souvent l'app quand l'autorisation change : la version voulue est
            // retenue, l'installation reprendra d'elle-même à la réouverture ([resumePending]).
            // Et sans reprise possible, la boîte revient à l'ouverture suivante.
            prefs().edit {
                remove(KEY_CHECKED_AT)
                putString(KEY_PENDING, update.version)
                putLong(KEY_PENDING_AT, System.currentTimeMillis())
            }
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        clearPending()
        job = scope.launch { performInstall(update) }
    }

    /** Rouvre la fenêtre de confirmation de la session en cours (quittée par Accueil, par exemple). */
    private fun reopenConfirmation() {
        val intent = confirmation ?: return
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Téléchargement puis dépôt de l'APK chez l'installateur d'Android. */
    private suspend fun performInstall(update: AppUpdate) {
        val apk = try {
            downloaded?.takeIf { (version, file) -> version == update.version && file.isFile }?.second
                ?: download(update).also { downloaded = update.version to it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _state.value = UpdateState.Failed(UpdateStep.Download, update)
            return
        }
        // Aucune session « en cours » tant que `commit` n'a pas créé la nouvelle : un retour à l'appli
        // pendant la copie de l'APK ne doit pas lire la session de l'essai précédent.
        installSession = NO_SESSION
        confirmation = null
        _state.value = UpdateState.Installing(update)
        try {
            withContext(Dispatchers.IO) { commit(apk) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _state.value = UpdateState.Failed(UpdateStep.Install, update)
        }
    }

    /**
     * Installation commencée avant que l'autorisation « applis inconnues » ne ferme l'app : si
     * l'autorisation est effective, on retrouve la version retenue et on l'installe sans clic
     * (« Reprise de la mise à jour… »). Vrai si une reprise est lancée. Une version retenue trop
     * ancienne, ou sans autorisation, est oubliée : rien ne s'installe à l'improviste plus tard.
     */
    private fun resumePending(): Boolean {
        val version = prefs().getString(KEY_PENDING, null) ?: return false
        val fresh = System.currentTimeMillis() - prefs().getLong(KEY_PENDING_AT, 0) in 0 until PENDING_MAX_MS
        clearPending()
        if (!fresh || !context.packageManager.canRequestPackageInstalls() || job?.isActive == true) return false
        job = scope.launch {
            _state.value = UpdateState.Resuming
            val update = try {
                fetchUpdate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value = UpdateState.Failed(UpdateStep.Check, null)
                return@launch
            }
            if (update != null && update.version == version) {
                // La boîte s'ouvre (au redémarrage, rien ne la montrait) : téléchargement, puis confirmation.
                _prompt.value = update
                performInstall(update)
            } else {
                _state.value = update?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            }
        }
        return true
    }

    private fun clearPending() = prefs().edit {
        remove(KEY_PENDING)
        remove(KEY_PENDING_AT)
    }

    /**
     * L'installateur demande une confirmation pour la session [sessionId] : retenue pour pouvoir la
     * rouvrir. Faux pour une autre session (abandonnée, ancienne) : rien à montrer.
     */
    internal fun rememberConfirmation(sessionId: Int, intent: Intent): Boolean {
        if (sessionId != installSession) return false
        confirmation = Intent(intent)
        return true
    }

    /** Réponse de l'installateur ([InstallReceiver]) pour la session [sessionId] ; les autres sont ignorées. */
    internal fun onInstallResult(status: Int, sessionId: Int) {
        // Abandonner l'ancienne session (nouvel essai) renvoie un refus sur son propre canal.
        if (sessionId != installSession) return
        val update = _state.value.pendingUpdate() ?: return
        _state.value = when (status) {
            // Confirmation refusée : « Installer » reprend avec l'APK déjà téléchargé.
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Aborted(update)
            else -> UpdateState.Failed(UpdateStep.Install, update)
        }
    }

    private suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(update, 0f)
        CoreSession.loadNativeLibrary()
        val dir = File(context.cacheDir, "update").apply {
            deleteRecursively()
            mkdirs()
        }
        val apk = File(dir, "hey-stream-${update.version}.apk")
        downloadAppUpdate(
            update,
            apk.absolutePath,
            object : UpdateProgress {
                override fun onProgress(received: ULong, total: ULong) {
                    val progress = if (total > 0u) (received.toDouble() / total.toDouble()).toFloat() else null
                    _state.value = UpdateState.Downloading(update, progress)
                }
            },
        )
        apk
    }

    /** Confie l'APK à l'installateur d'Android ; sa réponse arrive à [InstallReceiver]. */
    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Un essai précédent resté en suspens ne sert plus ; ses réponses (refus d'avoir été abandonné)
        // ne comptent pas : plus aucune session n'est « la bonne » le temps de l'abandonner.
        installSession = NO_SESSION
        confirmation = null
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Sans confirmation quand l'app a installé la version en place
            // (UPDATE_PACKAGES_WITHOUT_USER_ACTION) ; sinon Android la
            // demande quand même (STATUS_PENDING_USER_ACTION).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installSession = id
        installer.openSession(id).use { session ->
            session.openWrite("hey-stream.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            // Modifiable : l'installateur y ajoute son statut (déjà le cas
            // par défaut avant Android 12).
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            session.commit(pending.intentSender)
        }
    }

    private companion object {
        const val PREFS = "app_update"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_AUTO = "auto_check"
        const val KEY_PENDING = "pending_version"
        const val KEY_PENDING_AT = "pending_at"
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** Une autorisation donnée dans ce délai reprend l'installation ; au-delà, on l'oublie. */
        const val PENDING_MAX_MS = 10L * 60 * 1000

        const val NO_SESSION = -1
    }
}

/** Version en cours de mise à jour ou proposée, s'il y en a une. */
fun UpdateState.pendingUpdate(): AppUpdate? = when (this) {
    is UpdateState.Available -> update
    is UpdateState.Downloading -> update
    is UpdateState.Installing -> update
    is UpdateState.NeedsPermission -> update
    is UpdateState.Aborted -> update
    is UpdateState.Failed -> update
    else -> null
}

/**
 * Installation en cours ou qui attend l'utilisateur : une vérification automatique n'y touche pas.
 * Une installation annulée ([UpdateState.Aborted]) n'en fait pas partie : la recherche quotidienne
 * peut reproposer une version plus récente.
 */
internal fun UpdateState.installFlow(): Boolean = when (this) {
    is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.NeedsPermission, is UpdateState.Resuming -> true
    else -> false
}

/**
 * Réponse de l'installateur d'Android : demande de confirmation à montrer,
 * refus ou échec. Une installation réussie remplace l'app (rien à faire).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as HeyStreamApp).updater
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    updater.onInstallResult(PackageInstaller.STATUS_FAILURE, session)
                } else if (updater.rememberConfirmation(session, confirm)) {
                    val shown = runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    if (!shown) updater.onInstallResult(PackageInstaller.STATUS_FAILURE, session)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> updater.onInstallResult(status, session)
        }
    }
}

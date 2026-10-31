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
     * confirmation, s'il la demande). « Installer » le lui confie de nouveau
     * si rien ne s'est passé (confirmation bloquée en arrière-plan).
     */
    data class Installing(val update: AppUpdate) : UpdateState

    /** Android doit d'abord autoriser l'app à installer des applications. */
    data class NeedsPermission(val update: AppUpdate) : UpdateState

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
 * par jour au plus : une version remise à plus tard est reproposée le
 * lendemain.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Version proposée d'elle-même au lancement, dans une boîte. */
    private val _prompt = MutableStateFlow<AppUpdate?>(null)
    val prompt: StateFlow<AppUpdate?> = _prompt.asStateFlow()

    private var job: Job? = null

    /** APK déjà téléchargé et vérifié, pour un nouvel essai d'installation. */
    private var downloaded: Pair<String, File>? = null

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * À chaque retour au premier plan : reprend une installation qui
     * attendait l'autorisation d'Android, et vérifie s'il y a une mise à jour
     * une fois par jour au plus.
     */
    fun onForeground() {
        val state = _state.value
        if (state is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            install(state.update)
        }
        checkIfDue()
    }

    /** Jamais en debug : ni les tests ni l'émulateur n'interrogent GitHub. */
    private fun checkIfDue() {
        if (BuildConfig.DEBUG) return
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
                val update = withContext(Dispatchers.IO) {
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

    /** « Plus tard » : la boîte se ferme, la version sera reproposée le lendemain. */
    fun dismiss() {
        _prompt.value = null
        val state = _state.value
        if (state is UpdateState.Failed || state is UpdateState.NeedsPermission || state is UpdateState.Installing) {
            _state.value = state.pendingUpdate()?.let { UpdateState.Available(it) } ?: UpdateState.Idle
        }
    }

    /** Télécharge (sauf si c'est déjà fait) puis installe [update]. */
    fun install(update: AppUpdate) {
        if (job?.isActive == true) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            // Si Android relance l'app après l'autorisation, la boîte revient
            // à l'ouverture suivante.
            prefs().edit { remove(KEY_CHECKED_AT) }
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        job = scope.launch {
            val apk = try {
                downloaded?.takeIf { (version, file) -> version == update.version && file.isFile }?.second
                    ?: download(update).also { downloaded = update.version to it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value = UpdateState.Failed(UpdateStep.Download, update)
                return@launch
            }
            _state.value = UpdateState.Installing(update)
            try {
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value = UpdateState.Failed(UpdateStep.Install, update)
            }
        }
    }

    /** Réponse de l'installateur ([InstallReceiver]). */
    internal fun onInstallResult(status: Int) {
        val update = _state.value.pendingUpdate() ?: return
        _state.value = when (status) {
            // Confirmation refusée : la mise à jour reste proposée.
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(update)
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
        // Un essai précédent resté en suspens ne sert plus.
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
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

/** Version en cours de mise à jour ou proposée, s'il y en a une. */
fun UpdateState.pendingUpdate(): AppUpdate? = when (this) {
    is UpdateState.Available -> update
    is UpdateState.Downloading -> update
    is UpdateState.Installing -> update
    is UpdateState.NeedsPermission -> update
    is UpdateState.Failed -> update
    else -> null
}

/**
 * Réponse de l'installateur d'Android : demande de confirmation à montrer,
 * refus ou échec. Une installation réussie remplace l'app (rien à faire).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as HeyStreamApp).updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                val shown = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!shown) updater.onInstallResult(PackageInstaller.STATUS_FAILURE)
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> updater.onInstallResult(status)
        }
    }
}

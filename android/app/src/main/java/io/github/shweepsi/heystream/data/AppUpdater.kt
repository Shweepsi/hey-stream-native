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

    /** L'installateur d'Android a la main (confirmation, puis installation). */
    data class Installing(val update: AppUpdate) : UpdateState

    /** Android doit d'abord autoriser l'app à installer des applications. */
    data class NeedsPermission(val update: AppUpdate) : UpdateState

    data class Failed(val step: UpdateStep, val update: AppUpdate?) : UpdateState
}

/**
 * Mise à jour depuis les releases GitHub du projet. Le cœur trouve la
 * version la plus récente, télécharge et vérifie l'APK (taille, SHA-256) ;
 * ici, on le confie à l'installateur d'Android, qui demande confirmation.
 * L'APK étant signé par la même clé, Android le pose par-dessus l'app sans
 * rien effacer (abonnement, favoris, reprise).
 *
 * Au lancement (APK release seulement), une vérification par jour au plus ;
 * une version écartée par « Plus tard » n'est plus proposée d'elle-même.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Version proposée d'elle-même au lancement, dans une boîte. */
    private val _prompt = MutableStateFlow<AppUpdate?>(null)
    val prompt: StateFlow<AppUpdate?> = _prompt.asStateFlow()

    private var job: Job? = null

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Au lancement. Jamais en debug : ni les tests ni l'émulateur n'interrogent GitHub. */
    fun checkIfDue() {
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
                    checkAppUpdate(BuildConfig.VERSION_NAME)
                }
                prefs().edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }
                if (update == null) {
                    UpdateState.UpToDate
                } else {
                    if (automatic && prefs().getString(KEY_DISMISSED, null) != update.version) _prompt.value = update
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

    /** « Plus tard » : cette version n'est plus proposée d'elle-même. */
    fun dismiss() {
        _prompt.value?.let { prefs().edit { putString(KEY_DISMISSED, it.version) } }
        _prompt.value = null
        val state = _state.value
        if (state is UpdateState.Failed || state is UpdateState.NeedsPermission) {
            _state.value = state.pendingUpdate()?.let { UpdateState.Available(it) } ?: UpdateState.Idle
        }
    }

    /** Télécharge puis installe [update]. */
    fun install(update: AppUpdate) {
        if (job?.isActive == true) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            val apk = try {
                download(update)
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
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
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
        const val KEY_DISMISSED = "dismissed_version"
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

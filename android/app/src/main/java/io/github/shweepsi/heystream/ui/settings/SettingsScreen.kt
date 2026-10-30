package io.github.shweepsi.heystream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shweepsi.heystream.BuildConfig
import io.github.shweepsi.heystream.core.AppUpdate
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.CrashReports
import io.github.shweepsi.heystream.data.DevTools
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.heyStreamApp
import io.github.shweepsi.heystream.ui.components.ParentalPinDialog
import io.github.shweepsi.heystream.ui.components.PinPurpose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Réglages : catégories masquées, contrôle parental, langue, catalogue,
 * abonnement (et déconnexion), à propos.
 */
@Composable
fun SettingsScreen(onSignedOut: () -> Unit, onChangeServer: () -> Unit, onBack: () -> Unit) {
    val graph = appGraph()
    val viewModel = viewModel { SettingsViewModel(graph.session, graph.player) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var language by remember { mutableStateOf(LanguageChoice.of(AppCompatDelegate.getApplicationLocales().toLanguageTags())) }
    val context = LocalContext.current
    var crashReports by remember { mutableStateOf(CrashReports.isEnabled(context)) }
    var pinPurpose by remember { mutableStateOf<PinPurpose?>(null) }
    var shownLicense by remember { mutableStateOf<License?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnChangeServer by rememberUpdatedState(onChangeServer)
    val updater = heyStreamApp().updater
    val update by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var versionTaps by remember { mutableIntStateOf(0) }
    var devUnlocked by remember { mutableStateOf(DevTools.isUnlocked(context)) }
    var devTools by remember { mutableStateOf(DevToolsUi(recording = DevTools.isRecording(context))) }

    /** Lance une action des outils de test, puis relit le nombre de réponses enregistrées. */
    fun devAction(block: suspend () -> Unit) {
        scope.launch {
            val failed = try {
                block()
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                true
            }
            val summary = runCatching { DevTools.summary(context) }.getOrNull()
            devTools = devTools.copy(
                responses = summary?.responses?.toInt() ?: devTools.responses,
                bytes = summary?.bytes?.toLong() ?: devTools.bytes,
                error = failed,
            )
        }
    }

    // Nombre de réponses relu à chaque ouverture de « À propos ».
    LaunchedEffect(devUnlocked, state.section) {
        if (devUnlocked && state.section == SettingsSection.About) devAction {}
    }

    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
    BackHandler(onBack = onBack)

    val actions = remember(viewModel) {
        object : SettingsActions {
            override fun select(section: SettingsSection) = viewModel.select(section)

            override fun selectCategorySection(section: Section) = viewModel.selectCategorySection(section)

            override fun toggleCategory(category: CategoryToggle) = viewModel.toggle(category)

            override fun createPin() {
                pinPurpose = PinPurpose.Create
            }

            override fun changePin() {
                pinPurpose = PinPurpose.Change
            }

            override fun lockNow() = viewModel.lockNow()

            override fun language(choice: LanguageChoice) {
                if (choice == language) return
                language = choice
                // L'activité est recréée dans la nouvelle langue ; AppCompat la retient.
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(choice.tags))
            }

            override fun refreshCatalog() = viewModel.refreshCatalog()

            override fun signOut() {
                confirmSignOut = true
            }

            override fun changeServer() = currentOnChangeServer()

            override fun showLicense(license: License) {
                shownLicense = license
            }

            override fun crashReports(enabled: Boolean) {
                crashReports = enabled
                CrashReports.setEnabled(context, enabled)
            }

            override fun checkUpdate() = updater.check()

            override fun installUpdate(update: AppUpdate) = updater.install(update)

            override fun versionTapped() {
                if (devUnlocked) return
                versionTaps += 1
                if (versionTaps >= DevTools.UNLOCK_TAPS) {
                    DevTools.unlock(context)
                    devUnlocked = true
                }
            }

            override fun recording(enabled: Boolean) {
                devTools = devTools.copy(recording = enabled)
                devAction { DevTools.setRecording(context, graph.session, enabled) }
            }

            override fun shareRecording() = devAction { DevTools.share(context) }

            override fun clearRecording() = devAction { DevTools.clear(context) }

            override fun back() = currentOnBack()
        }
    }

    SettingsContent(
        state = state,
        language = language,
        appVersion = BuildConfig.VERSION_NAME,
        actions = actions,
        crashReports = crashReports,
        update = update,
        devTools = devTools.takeIf { devUnlocked },
    )

    pinPurpose?.let { purpose ->
        // Créer ou changer le code depuis les réglages ne déverrouille rien.
        ParentalPinDialog(
            purpose = purpose,
            onSuccess = { pinPurpose = null },
            onDismiss = { pinPurpose = null },
            unlockAfterSet = false,
        )
    }
    shownLicense?.let { shown -> LicenseDialog(shown, onDismiss = { shownLicense = null }) }
    if (confirmSignOut) {
        SignOutDialog(
            busy = state.signingOut,
            onConfirm = viewModel::signOut,
            onDismiss = { confirmSignOut = false },
        )
    }
}

package io.github.shweepsi.heystream.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.AppUpdate
import io.github.shweepsi.heystream.data.UpdateState
import io.github.shweepsi.heystream.data.UpdateStep
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.capture
import io.github.shweepsi.heystream.ui.settings.DevToolsUi
import io.github.shweepsi.heystream.ui.settings.LanguageChoice
import io.github.shweepsi.heystream.ui.settings.SettingsActions
import io.github.shweepsi.heystream.ui.settings.SettingsContent
import io.github.shweepsi.heystream.ui.settings.SettingsSection
import io.github.shweepsi.heystream.ui.settings.SettingsState
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val update = AppUpdate(
        version = "0.6.0",
        notes = "- Mise à jour depuis **GitHub** dans Réglages › À propos.\n- Outils de test cachés.\n\n---\nHey Stream est uniquement un lecteur.",
        apkUrl = "https://example.invalid/hey-stream-0.6.0.apk",
        size = 24_000_000uL,
        sha256 = null,
        token = null,
    )

    @Test
    fun releaseNotesDropThePublicationFooter() {
        assertEquals(
            "• Mise à jour depuis GitHub dans Réglages › À propos.\n• Outils de test cachés.",
            releaseNotes(update.notes),
        )
        assertEquals("", releaseNotes(""))
    }

    @Test
    fun releaseNotesJoinContinuationLines() {
        val notes = "Trois défauts relevés à la télécommande (essais des\n2 et 3 novembre).\n\n" +
            "- Après une fermeture par Android, la télécommande\n  retrouve le focus à la réouverture.\n" +
            "- Le premier favori fait apparaître la ligne Favoris\n  tout de suite, et le dernier retiré\n  la fait disparaître.\n" +
            "- Un point court.\n\n---\nPied."
        assertEquals(
            "Trois défauts relevés à la télécommande (essais des 2 et 3 novembre).\n\n" +
                "• Après une fermeture par Android, la télécommande retrouve le focus à la réouverture.\n" +
                "• Le premier favori fait apparaître la ligne Favoris tout de suite, et le dernier retiré la fait disparaître.\n" +
                "• Un point court.",
            releaseNotes(notes),
        )
    }

    @Test
    fun promptInstallsOrPostpones() {
        var installed = 0
        var later = 0
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.Available(update), onInstall = { installed++ }, onLater = { later++ })
            }
        }
        compose.onNodeWithText("Hey Stream 0.6.0 is available").assertIsDisplayed()
        compose.onNodeWithText("Download and install").performClick()
        compose.onNodeWithText("Later").performClick()
        assertEquals(1 to 1, installed to later)
    }

    @Test
    fun noSecondDownloadWhileOneRuns() {
        var installed = 0
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.Downloading(update, 0.42f), onInstall = { installed++ }, onLater = {})
            }
        }
        // Le libellé du bouton dit l'avancement ; un appui n'ouvre pas un deuxième téléchargement.
        compose.onNodeWithText("Downloading… 42%").assertIsDisplayed().performClick()
        assertEquals(0, installed)
    }

    @Test
    fun noSecondInstallSessionWhileAndroidAsksToConfirm() {
        var installed = 0
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.Installing(update), onInstall = { installed++ }, onLater = {})
            }
        }
        compose.onNodeWithText("Android will ask you to confirm: choose Install (right arrow). Hey Stream then closes: open it again.").assertIsDisplayed()
        compose.onNodeWithText("Confirm in Android’s window").assertIsDisplayed().performClick()
        assertEquals(0, installed)
    }

    @Test
    fun cancelledInstallationCanBeResumedWithTheDownloadedFile() {
        var installed = 0
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.Aborted(update), onInstall = { installed++ }, onLater = {})
            }
        }
        compose.onNodeWithText("Installation cancelled. Press Install again to resume.").assertIsDisplayed()
        compose.onNodeWithText("Install").performClick()
        assertEquals(1, installed)
    }

    @Test
    fun permissionHelpExplainsTheScreenThenTheProfileCase() {
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.NeedsPermission(update), onInstall = {}, onLater = {})
            }
        }
        compose.onNodeWithText("In the Android screen that opened, scroll down to Hey Stream before pressing OK, then allow it. Android then closes the app: reopen it, the update resumes on its own.").assertIsDisplayed()
    }

    @Test
    fun deniedPermissionPointsToTheApkFile() {
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.NeedsPermission(update, denied = true), onInstall = {}, onLater = {})
            }
        }
        compose.onNodeWithText("Allow Hey Stream in the Android screen. If the switch has no effect, install the update from the APK file on the project’s GitHub page.").assertIsDisplayed()
    }

    @Test
    fun resumingShowsItsOwnLabel() {
        var installed = 0
        compose.setContent {
            HeyStreamTheme {
                UpdatePanel(update, UpdateState.Resuming, onInstall = { installed++ }, onLater = {})
            }
        }
        compose.onNodeWithText("Resuming the update…").assertIsDisplayed().performClick()
        assertEquals(0, installed)
    }

    @Test
    fun autoCheckSwitchIsOffered() {
        var chosen: Boolean? = null
        val actions = object : SettingsActions {
            override fun autoUpdate(enabled: Boolean) {
                chosen = enabled
            }
        }
        compose.setContent {
            HeyStreamTheme {
                SettingsContent(
                    SettingsState(section = SettingsSection.About),
                    LanguageChoice.System,
                    appVersion = "0.5.2",
                    actions = actions,
                    autoUpdate = true,
                )
            }
        }
        compose.onNodeWithText("Check for updates automatically").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("At most once a day, when the app opens. Turned off: only the button below checks.").performScrollTo().assertIsDisplayed()
        // Activée au départ : un appui la coupe.
        assertEquals(false, chosen)
    }

    @Test
    fun aboutOffersTheNewVersion() {
        var installing: AppUpdate? = null
        val actions = object : SettingsActions {
            override fun installUpdate(update: AppUpdate) {
                installing = update
            }
        }
        compose.setContent {
            HeyStreamTheme {
                SettingsContent(
                    SettingsState(section = SettingsSection.About),
                    LanguageChoice.System,
                    appVersion = "0.5.2",
                    actions = actions,
                    update = UpdateState.Available(update),
                )
            }
        }
        compose.onNodeWithText("Version 0.6.0 is available.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download and install version 0.6.0").performScrollTo().performClick()
        assertEquals(update, installing)
    }

    @Test
    fun failedCheckIsExplained() {
        compose.setContent {
            HeyStreamTheme {
                SettingsContent(
                    SettingsState(section = SettingsSection.About),
                    LanguageChoice.System,
                    appVersion = "0.5.2",
                    actions = object : SettingsActions {},
                    update = UpdateState.Failed(UpdateStep.Check, null),
                )
            }
        }
        compose.onNodeWithText("Couldn’t check for updates. Check your connection and try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsDisplayed()
    }

    @Test fun promptTablet() = compose.capture("update_prompt", Device.Tablet, locale = "fr") {
        UpdatePanel(update, UpdateState.Downloading(update, 0.42f), onInstall = {}, onLater = {})
    }

    @Test fun aboutWithTestToolsPhone() = compose.capture("settings_about_tools", Device.Phone, locale = "fr") {
        SettingsContent(
            SettingsState(section = SettingsSection.About, coreVersion = "0.6.0"),
            LanguageChoice.French,
            appVersion = "0.6.0",
            actions = object : SettingsActions {},
            update = UpdateState.UpToDate,
            devTools = DevToolsUi(recording = true, responses = 128, bytes = 2_400_000),
        )
    }
}

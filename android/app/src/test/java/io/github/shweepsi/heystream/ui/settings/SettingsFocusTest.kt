package io.github.shweepsi.heystream.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Télécommande : Robolectric reste en mode tactile, où rien ne prend le focus. */
private val KeyboardInput = object : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/** Parcours des réglages à la télécommande (TV), sur un état factice. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun categories(section: Section) = CategoriesUi(
        section = section,
        items = (1..3).map { CategoryToggle("$it", "${section.name} $it", hidden = false, locked = false) },
    )

    private var state by mutableStateOf(
        SettingsState(
            categories = categories(Section.MOVIES),
            parental = ParentalUi(loaded = true, hasPin = true, unlocked = true),
        ),
    )

    private val actions = object : SettingsActions {
        override fun select(section: SettingsSection) {
            state = state.copy(section = section)
        }

        override fun selectCategorySection(section: Section) {
            state = state.copy(categories = categories(section))
        }
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true) {
                    SettingsContent(state, LanguageChoice.System, appVersion = "0.1.0", actions = actions)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Touche envoyée à la boîte de dialogue (sa fenêtre est la dernière racine). */
    private fun pressInDialog(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun clickable(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasClickAction())

    @Test
    fun remoteMovesBetweenSectionsAndContent() {
        show()
        clickable("Hidden categories").assertIsFocused()

        // Droite : l'onglet ouvert (Films), pas le premier.
        press(Key.DirectionRight)
        clickable("Movies").assertIsFocused()
        assertEquals(Section.MOVIES, state.categories.section)

        press(Key.DirectionDown)
        clickable("MOVIES 1").assertIsFocused()

        // Haut : l'onglet ouvert, sans changer de section.
        press(Key.DirectionUp)
        clickable("Movies").assertIsFocused()
        assertEquals(Section.MOVIES, state.categories.section)
        press(Key.DirectionDown)
        clickable("MOVIES 1").assertIsFocused()

        // Retour : la rubrique.
        back()
        clickable("Hidden categories").assertIsFocused()

        // Droite à nouveau : la catégorie quittée.
        press(Key.DirectionRight)
        clickable("MOVIES 1").assertIsFocused()

        // Gauche : la rubrique ouverte.
        press(Key.DirectionLeft)
        clickable("Hidden categories").assertIsFocused()

        // Bas : la rubrique suivante s'ouvre au focus.
        press(Key.DirectionDown)
        clickable("Parental control").assertIsFocused()
        assertEquals(SettingsSection.Parental, state.section)

        press(Key.DirectionRight)
        clickable("Change the code").assertIsFocused()
        press(Key.DirectionRight)
        clickable("Lock now").assertIsFocused()
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        clickable("Parental control").assertIsFocused()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        clickable("Catalog").assertIsFocused()
        press(Key.DirectionRight)
        clickable("Update now").assertIsFocused()
        back()
        clickable("Catalog").assertIsFocused()

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        clickable("About").assertIsFocused()
        press(Key.DirectionRight)
        clickable("Send anonymous crash reports").assertIsFocused()
        press(Key.DirectionDown)
        clickable("Check for updates").assertIsFocused()
        press(Key.DirectionDown)
        clickable("Inter (typeface)").assertIsFocused()

        // Retour aux catégories masquées : l'onglet ouvert reste Films.
        back()
        repeat(5) { press(Key.DirectionUp) }
        clickable("Hidden categories").assertIsFocused()
        press(Key.DirectionRight)
        clickable("Movies").assertIsFocused()
        assertEquals(Section.MOVIES, state.categories.section)
    }

    @Test
    fun languageFocusStartsOnTheChosenLanguage() {
        state = state.copy(section = SettingsSection.Language)
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true) {
                    SettingsContent(state, LanguageChoice.English, appVersion = "0.1.0", actions = actions)
                }
            }
        }
        compose.waitForIdle()
        clickable("Language").assertIsFocused()
        press(Key.DirectionRight)
        clickable("English").assertIsFocused()
        press(Key.DirectionUp)
        clickable("Français").assertIsFocused()
        press(Key.DirectionLeft)
        clickable("Language").assertIsFocused()
    }

    @Test
    fun lockNowMovesFocusToTheRemainingButton() {
        val remote = object : SettingsActions by actions {
            override fun lockNow() {
                state = state.copy(parental = state.parental.copy(unlocked = false))
            }
        }
        state = state.copy(section = SettingsSection.Parental)
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true) {
                    SettingsContent(state, LanguageChoice.System, appVersion = "0.1.0", actions = remote)
                }
            }
        }
        compose.waitForIdle()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        clickable("Lock now").assertIsFocused()
        press(Key.DirectionCenter)
        clickable("Change the code").assertIsFocused()
    }

    @Test
    fun licenseTextScrollsThenReachesClose() {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true) {
                    LicenseDialog(License.Inter, onDismiss = {})
                }
            }
        }
        // Le texte est lu dans les assets hors du fil principal : on l'attend.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("SIL Open Font License, Version 1.1", substring = true))
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        // Le texte a le focus (Bas le fait défiler), pas « Fermer ».
        clickable("Close").assertIsNotFocused()
        pressInDialog(Key.DirectionDown)
        clickable("Close").assertIsNotFocused()
        repeat(40) { pressInDialog(Key.DirectionDown) }
        clickable("Close").assertIsFocused()
        pressInDialog(Key.DirectionUp)
        clickable("Close").assertIsNotFocused()
        compose.onNode(isFocused()).assertExists()
    }

    @Test
    fun signOutConfirmationFocusesCancel() {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true) {
                    SignOutDialog(busy = false, onConfirm = {}, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
        clickable("Cancel").assertIsFocused()
    }
}

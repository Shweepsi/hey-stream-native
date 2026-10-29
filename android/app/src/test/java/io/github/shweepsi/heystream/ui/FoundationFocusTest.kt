package io.github.shweepsi.heystream.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.ui.components.PinPad
import io.github.shweepsi.heystream.ui.home.HomeContent
import io.github.shweepsi.heystream.ui.home.HomeTarget
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Focus à la télécommande des écrans du socle (accueil, code PIN). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class FoundationFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private fun tv(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true, systemDark = true) { content() }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun homeFocusesTheLiveTileFirst() {
        tv { HomeContent(null, {}, {}, {}, {}, {}) }
        compose.onNode(hasText("Live", substring = true) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun homeGivesTheFocusBackToWhatWasOpened() {
        tv { HomeContent(null, {}, {}, {}, {}, {}, focusOn = HomeTarget.Settings) }
        compose.onNode(hasText("Settings") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun pinKeysKeepTheFocusWhileTheCodeIsChecked() {
        // Vérification en cours (enabled = false) : le focus reste sur le pavé,
        // il ne saute pas sur « Annuler ».
        tv { PinPad("Code", null, entered = 0, error = null, enabled = false, onDigit = {}, onDelete = {}, onCancel = {}) }
        compose.onNode(hasText("1") and hasClickAction()).assertIsFocused()
    }
}

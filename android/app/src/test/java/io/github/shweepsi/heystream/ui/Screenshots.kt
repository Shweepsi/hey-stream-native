package io.github.shweepsi.heystream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import io.github.shweepsi.heystream.ui.theme.Hs
import org.robolectric.RuntimeEnvironment

/**
 * Appareils des captures d'écran (`./gradlew recordRoborazziDebug`, images
 * dans `app/build/outputs/roborazzi/`). Hors enregistrement, les tests
 * vérifient seulement que l'écran se compose sans erreur.
 */
enum class Device(val qualifiers: String, val isTv: Boolean, val dark: Boolean) {
    /** Mi Box S : 1920 × 1080 px, 960 × 540 dp. */
    Tv("w960dp-h540dp-land-television-xhdpi", isTv = true, dark = true),

    /** Xiaomi Pad 7 (paysage), thème clair. */
    Tablet("w1280dp-h800dp-land-xhdpi", isTv = false, dark = false),

    /** Tablette en thème sombre. */
    TabletDark("w1280dp-h800dp-land-xhdpi", isTv = false, dark = true),

    /** Téléphone en paysage. */
    Phone("w800dp-h360dp-land-xxhdpi", isTv = false, dark = true),
}

/** Télécommande : Robolectric reste en mode tactile, où rien ne prend le focus. */
private val KeyboardInput = object : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/**
 * Compose [content] dans le thème de [device] et enregistre `name_device.png`.
 * Sur TV, l'écran est piloté à la télécommande : le focus demandé par
 * l'écran (`FocusRequester`) est visible.
 */
fun ComposeContentTestRule.capture(name: String, device: Device, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers(device.qualifiers)
    setContent {
        val screen = @Composable {
            HeyStreamTheme(isTv = device.isTv, systemDark = device.dark) {
                Box(Modifier.fillMaxSize().background(Hs.colors.background)) { content() }
            }
        }
        if (device.isTv) {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput, content = screen)
        } else {
            screen()
        }
    }
    waitForIdle()
    onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${device.name.lowercase()}.png")
}

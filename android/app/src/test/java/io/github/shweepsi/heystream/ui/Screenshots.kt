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
enum class Device(val qualifiers: String, val isTv: Boolean) {
    /** Mi Box S : 1920 × 1080 px, 960 × 540 dp. */
    Tv("w960dp-h540dp-land-television-xhdpi", isTv = true),

    /** Xiaomi Pad 7 (paysage). */
    Tablet("w1280dp-h800dp-land-xhdpi", isTv = false),

    /** Téléphone récent en paysage (Pixel 8 Pro, Xiaomi 14 : environ 915 × 412 dp). */
    Phone("w915dp-h412dp-land-xxhdpi", isTv = false),
}

/** Télécommande : Robolectric reste en mode tactile, où rien ne prend le focus. */
val KeyboardInput = object : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/**
 * Compose [content] dans le thème de [device] et enregistre `name_device.png`
 * (`name_device_fr.png` en français si [locale] vaut `"fr"`). Sur TV, l'écran
 * est piloté à la télécommande : le focus demandé par l'écran
 * (`FocusRequester`) est visible.
 */
fun ComposeContentTestRule.capture(
    name: String,
    device: Device,
    locale: String? = null,
    content: @Composable () -> Unit,
) {
    RuntimeEnvironment.setQualifiers(listOfNotNull(locale, device.qualifiers).joinToString("-"))
    setContent {
        val screen = @Composable {
            HeyStreamTheme(isTv = device.isTv) {
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
    val suffix = listOfNotNull(device.name.lowercase(), locale).joinToString("_")
    onRoot().captureRoboImage("build/outputs/roborazzi/${name}_$suffix.png")
}

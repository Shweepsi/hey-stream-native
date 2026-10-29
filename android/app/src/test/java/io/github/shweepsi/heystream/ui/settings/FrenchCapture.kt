package io.github.shweepsi.heystream.ui.settings

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
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import io.github.shweepsi.heystream.ui.theme.Hs
import org.robolectric.RuntimeEnvironment

/** Télécommande : Robolectric reste en mode tactile, où rien ne prend le focus. */
private val KeyboardInput = object : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/**
 * Comme `capture` (ui/Screenshots.kt), mais en français : les textes
 * français, plus longs, doivent tenir aussi. Image `name_device_fr.png`.
 */
fun ComposeContentTestRule.captureFrench(name: String, device: Device, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers("fr-${device.qualifiers}")
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
    onRoot().captureRoboImage("build/outputs/roborazzi/${name}_${device.name.lowercase()}_fr.png")
}

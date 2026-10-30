package io.github.shweepsi.heystream.debug

import androidx.compose.runtime.Composable

/** Outils de l'APK debug : aucun dans l'APK release (voir `src/debug`). */
object DebugTools {
    val catalogSettings: (@Composable () -> Unit)? = null
}

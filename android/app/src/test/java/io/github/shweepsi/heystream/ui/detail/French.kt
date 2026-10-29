package io.github.shweepsi.heystream.ui.detail

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/** Captures en français (langue du propriétaire), sans toucher aux appareils communs. */
@Composable
fun InFrench(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val parent = LocalConfiguration.current
    val config = remember(parent) { Configuration(parent).apply { setLocale(Locale.FRANCE) } }
    val context = remember(base, config) { base.createConfigurationContext(config) }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides config,
        LocalResources provides context.resources,
        content = content,
    )
}

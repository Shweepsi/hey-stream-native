package io.github.shweepsi.heystream.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalHsColors = staticCompositionLocalOf { DarkColors }
val LocalHsType = staticCompositionLocalOf { TvType }
val LocalHsDimens = staticCompositionLocalOf { TvDimens }

/** Jetons du design (docs/DESIGN.md), lus dans les composables. */
object Hs {
    val colors: HsColors
        @Composable @ReadOnlyComposable
        get() = LocalHsColors.current

    val type: HsType
        @Composable @ReadOnlyComposable
        get() = LocalHsType.current

    val dimens: HsDimens
        @Composable @ReadOnlyComposable
        get() = LocalHsDimens.current

    /** Télécommande d'abord : TV (Android TV, Google TV). */
    val isTv: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalHsDimens.current.isTv
}

/** Vrai sur un téléviseur (Android TV, Google TV, Fire TV). */
fun Context.isTelevision(): Boolean {
    val uiMode = getSystemService(UiModeManager::class.java)?.currentModeType
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/**
 * Thème de l'app : toujours sombre sur TV, sinon celui du système. Les
 * tailles suivent l'appareil (TV : écran de 960 × 540 dp ; tablette et
 * téléphone : valeurs tactiles).
 */
@Composable
fun HeyStreamTheme(
    isTv: Boolean = LocalContext.current.let { context -> remember(context) { context.isTelevision() } },
    systemDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (isTv || systemDark) DarkColors else LightColors
    val type = if (isTv) TvType else TouchType
    val dimens = if (isTv) TvDimens else TouchDimens
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.text,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerHigh = colors.surface,
            outline = colors.separator,
        )
    } else {
        lightColorScheme(
            primary = colors.text,
            onPrimary = colors.background,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainerHigh = colors.surface,
            outline = colors.separator,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = type.toMaterial()) {
        CompositionLocalProvider(
            LocalHsColors provides colors,
            LocalHsType provides type,
            LocalHsDimens provides dimens,
            LocalContentColor provides colors.text,
            LocalTextStyle provides type.body,
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = HsColors.Focus,
                backgroundColor = HsColors.Focus.copy(alpha = 0.35f),
            ),
            content = content,
        )
    }
}

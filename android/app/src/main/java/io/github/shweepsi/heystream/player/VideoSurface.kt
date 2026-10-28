package io.github.shweepsi.heystream.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import io.github.shweepsi.heystream.ui.theme.HsColors

/**
 * Image du lecteur, au format de la vidéo. SurfaceView : le plus léger pour
 * le GPU de la Mi Box S. L'écran reste allumé tant que [keepScreenOn].
 *
 * Aperçu → plein écran : garder ce composable et changer seulement sa
 * taille (même lecteur, aucun fondu entre deux lecteurs).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier, keepScreenOn: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    Box(modifier.background(HsColors.Video)) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
        )
    }
}

package io.github.shweepsi.heystream.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.shweepsi.heystream.player.VideoSurface
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ParentalPinDialog
import io.github.shweepsi.heystream.ui.components.rememberNow
import io.github.shweepsi.heystream.ui.theme.Hs

/**
 * Direct : catégories | chaînes | aperçu vidéo et programme. [initialChannelId]
 * (depuis la recherche) ouvre cette chaîne directement en plein écran.
 */
@Composable
fun LiveScreen(initialChannelId: Long?, onBack: () -> Unit) {
    val graph = appGraph()
    val player = graph.player
    val viewModel = viewModel { LiveViewModel(graph.session, graph.player, initialChannelId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val now by rememberNow()

    // Quitter le direct ferme le flux (une seule connexion par abonnement).
    val leave = remember(viewModel, onBack) {
        {
            viewModel.onLeave()
            onBack()
        }
    }
    BackHandler {
        if (viewModel.state.value.fullscreen) viewModel.onExitFullscreen() else leave()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onLeave() }
    }
    LaunchedEffect(viewModel) { viewModel.resumeIfStopped() }
    if (!Hs.isTv) HideSystemBars(state.fullscreen)

    // Écran allumé seulement pendant la lecture (la mise en veille reste possible sans flux).
    val keepScreenOn = state.playback.let { it is LivePlayback.Playing || it is LivePlayback.Reconnecting }
    LiveContent(
        state = state,
        channels = channels,
        nowSecs = now / 1000,
        actions = viewModel,
        onBack = leave,
        video = { modifier -> VideoSurface(player.player, modifier, keepScreenOn = keepScreenOn) },
    )

    state.pinRequest?.let { request ->
        key(request) {
            ParentalPinDialog(
                purpose = request.purpose,
                onSuccess = viewModel::onPinSuccess,
                onDismiss = viewModel::onPinDismiss,
            )
        }
    }
}

/** Plein écran au doigt : barres du système masquées (un glissement les montre). */
@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        if (!hidden || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

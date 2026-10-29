package io.github.shweepsi.heystream.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.player.VideoSurface
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.theme.Hs
import kotlinx.coroutines.delay

/** Les commandes se masquent après ce délai sans touche (pendant la lecture). */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/** Position relue sur le lecteur, commandes affichées. */
private const val POSITION_REFRESH_MS = 500L

/**
 * L'écran reste allumé seulement pendant la lecture, ou pendant le
 * chargement et les reconnexions : en pause, après un échec ou sur un
 * message d'erreur, la mise en veille (et l'écran de veille de la TV)
 * reste possible.
 */
internal fun keepsScreenOn(state: PlayerUiState, status: PlaybackStatus): Boolean =
    state.error == null && !status.failed && (status.playing || status.buffering)

/**
 * Lecteur plein écran d'un film ou d'un épisode : le lecteur unique de
 * l'app, ses sous-titres, et les commandes de [PlayerContent].
 *
 * Les arguments ne font que remplir le `SavedStateHandle` de l'entrée de
 * navigation à sa création ([PlayerArgs]) : restauré après l'arrêt de
 * l'app, l'écran relit le handle (épisode enchaîné, position atteinte),
 * pas la route d'origine.
 */
@Composable
fun PlayerScreen(kind: PlaybackKind, id: Long, seriesId: Long?, fromStart: Boolean, onExit: () -> Unit) {
    val graph = appGraph()
    val controller = graph.player
    val viewModel = viewModel {
        val handle = PlayerArgs.seed(createSavedStateHandle(), kind, id, seriesId, fromStart)
        PlayerViewModel(graph.session, controller, handle)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val chrome = remember { PlayerChrome(visible = true) }
    val player = controller.player

    // Une seule sortie : position enregistrée, flux fermé, puis retour.
    val currentOnExit by rememberUpdatedState(onExit)
    var exited by remember { mutableStateOf(false) }
    val exit = remember(viewModel) {
        {
            if (!exited) {
                exited = true
                viewModel.close()
                currentOnExit()
            }
        }
    }
    LaunchedEffect(state.exit) { if (state.exit) exit() }

    // Position et durée, lues seulement quand les commandes sont visibles.
    var position by remember { mutableStateOf(PlaybackPosition()) }
    val readPosition = remember(player) {
        {
            position = PlaybackPosition(
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            )
        }
    }
    LaunchedEffect(chrome.visible, player) {
        while (chrome.visible) {
            readPosition()
            delay(POSITION_REFRESH_MS)
        }
    }

    // Masquage automatique pendant la lecture, relancé à chaque touche.
    LaunchedEffect(chrome.visible, chrome.menu, chrome.interaction, status.playing) {
        if (chrome.visible && chrome.menu == null && status.playing) {
            delay(CONTROLS_TIMEOUT_MS)
            chrome.hide()
        }
    }

    // Arrière-plan : position enregistrée avant que le flux ne soit fermé, et
    // aucun flux ouvert avant le retour au premier plan.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    ImmersiveMode()

    BackHandler {
        when {
            state.next != null -> viewModel.cancelNext()
            chrome.menu != null -> chrome.closeMenu()
            chrome.visible && state.error == null && !status.failed -> chrome.hide()
            else -> exit()
        }
    }

    val actions = remember(viewModel) {
        object : PlayerActions {
            override fun togglePause() = viewModel.togglePause()

            // La barre suit aussitôt (sans attendre la relecture suivante).
            override fun seekBy(deltaMs: Long) {
                viewModel.seekBy(deltaMs)
                readPosition()
            }

            override fun seekTo(fraction: Float) {
                viewModel.seekToFraction(fraction)
                readPosition()
            }

            override fun selectTrack(key: TrackKey) = viewModel.selectTrack(key)

            override fun disableSubtitles() = viewModel.disableSubtitles()

            override fun retry() = viewModel.retry()

            override fun playNext() = viewModel.playNextNow()

            override fun cancelNext() = viewModel.cancelNext()

            override fun exit() = exit()
        }
    }

    PlayerContent(
        state = state,
        status = status,
        position = position,
        chrome = chrome,
        actions = actions,
        video = {
            Box(Modifier.fillMaxSize()) {
                VideoSurface(player, Modifier.fillMaxSize(), keepScreenOn = keepsScreenOn(state, status))
                Subtitles(player, raised = chrome.visible, modifier = Modifier.fillMaxSize())
            }
        },
    )
}

/**
 * Sous-titres intégrés au flux (texte ou image : DVB, PGS), dessinés par
 * le `SubtitleView` de Media3. Ils remontent au-dessus des commandes.
 */
@OptIn(UnstableApi::class)
@Composable
private fun Subtitles(player: Player, raised: Boolean, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<SubtitleView?>(null) }
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                setUserDefaultStyle()
                setUserDefaultTextSize()
            }.also { view = it }
        },
        update = { it.setBottomPaddingFraction(if (raised) RAISED_SUBTITLES else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION) },
        modifier = modifier,
    )
    val subtitles = view ?: return
    DisposableEffect(player, subtitles) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                subtitles.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        subtitles.setCues(player.currentCues.cues)
        onDispose {
            player.removeListener(listener)
            subtitles.setCues(null)
        }
    }
}

/** Part de la hauteur laissée sous les sous-titres quand les commandes sont visibles. */
private const val RAISED_SUBTITLES = 0.3f

/** Au doigt, la vidéo occupe tout l'écran : barres système masquées (un glissement les rappelle). */
@Composable
private fun ImmersiveMode() {
    if (Hs.isTv) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

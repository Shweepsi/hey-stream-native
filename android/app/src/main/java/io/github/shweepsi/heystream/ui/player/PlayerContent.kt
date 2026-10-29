package io.github.shweepsi.heystream.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.FocusScale
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsIconButton
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.PanelLabel
import io.github.shweepsi.heystream.ui.components.ProgressRail
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular
import kotlin.math.roundToInt

/** Position de lecture, lue sur le lecteur ; [durationMs] vaut 0 si inconnue. */
data class PlaybackPosition(val positionMs: Long = 0, val durationMs: Long = 0)

/** Liste de pistes ouverte par-dessus les commandes. */
enum class TrackMenu { Audio, Subtitles }

/** Commande qui reçoit le focus quand les commandes apparaissent. */
enum class ControlFocus { PlayPause, Seek, Audio, Subtitles }

/** Commandes affichées ou masquées, liste de pistes ouverte : état de l'interface seule. */
@Stable
class PlayerChrome(visible: Boolean = true, menu: TrackMenu? = null, focus: ControlFocus = ControlFocus.PlayPause) {
    var visible by mutableStateOf(visible)
        private set
    var menu by mutableStateOf(menu)
        private set
    var focus by mutableStateOf(focus)
        private set

    /** Change à chaque touche ou toucher : relance le masquage automatique. */
    var interaction by mutableIntStateOf(0)
        private set

    fun show(focus: ControlFocus = ControlFocus.PlayPause) {
        visible = true
        this.focus = focus
        poke()
    }

    fun hide() {
        visible = false
        menu = null
    }

    fun openMenu(menu: TrackMenu) {
        this.menu = menu
        focus = if (menu == TrackMenu.Audio) ControlFocus.Audio else ControlFocus.Subtitles
        poke()
    }

    fun closeMenu() {
        menu = null
        poke()
    }

    fun poke() {
        interaction++
    }
}

/**
 * Volume du média et luminosité de l'écran (0 à 1), réglés au doigt par un
 * glissement vertical : à droite le volume, à gauche la luminosité.
 */
interface PlayerLevels {
    var volume: Float
    var brightness: Float
}

/** Ce que l'écran demande au lecteur et à la navigation. */
interface PlayerActions {
    fun togglePause()

    /** Avance (+30 s) ou recul (−10 s), cumulable. */
    fun nudge(forward: Boolean)

    fun seekTo(fraction: Float)

    fun selectTrack(key: TrackKey)

    fun disableSubtitles()

    fun retry()

    fun playNext()

    fun cancelNext()

    fun exit()
}

/**
 * Lecteur plein écran, sans logique : vidéo ([video]), commandes, choix des
 * pistes, épisode suivant, reconnexion et échec. Toujours en thème sombre
 * (sur la vidéo, les couleurs ne suivent pas le thème).
 *
 * Télécommande : OK met en pause et montre les commandes ; gauche / droite
 * avancent (30 s) ou reculent (10 s), appuis cumulés, même commandes
 * masquées ; haut / bas montrent les commandes. Au doigt : double tap sur un
 * côté de la vidéo.
 */
@Composable
fun PlayerContent(
    state: PlayerUiState,
    status: PlaybackStatus,
    position: PlaybackPosition,
    chrome: PlayerChrome,
    actions: PlayerActions,
    modifier: Modifier = Modifier,
    /** Absent sur TV et dans les tests : pas de glissement vertical. */
    levels: PlayerLevels? = null,
    video: @Composable () -> Unit = {},
) {
    HeyStreamTheme(isTv = Hs.isTv) {
        val failed = state.error != null || (status.failed && status.reconnectAttempt == 0 && !state.retrying)
        val blocking = failed || state.next != null || chrome.menu != null
        val root = remember { FocusRequester() }
        val focus = remember { ControlFocusRequesters() }
        // Touche dont le relâchement ne doit pas atteindre la commande qui vient de prendre le focus.
        val swallowUp = remember { arrayOfNulls<Key>(1) }
        val controlsShown = chrome.visible && !failed && state.next == null
        val seeking = rememberUpdatedState(state.pendingSeekMs != null)
        val haptic = LocalHapticFeedback.current
        // Dernier tap d'avance ou de recul au doigt : l'onde part de là.
        var flash by remember { mutableStateOf<TapFlash?>(null) }
        // Glissement vertical en cours (volume ou luminosité) et niveau affiché.
        var adjusting by remember { mutableStateOf<LevelKind?>(null) }
        var level by remember { mutableFloatStateOf(0f) }
        val tapNudge = { forward: Boolean, at: Offset ->
            actions.nudge(forward)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            flash = TapFlash(forward, at, (flash?.id ?: 0) + 1)
        }

        LaunchedEffect(controlsShown, chrome.focus, chrome.menu) {
            withFrameNanos { }
            when {
                failed || state.next != null || chrome.menu != null -> Unit
                controlsShown -> {
                    // Bouton absent (une seule piste audio) : lecture / pause.
                    val moved = runCatching { focus.of(chrome.focus).requestFocus() }.getOrDefault(false)
                    if (!moved) runCatching { focus.playPause.requestFocus() }
                }
                else -> runCatching { root.requestFocus() }
            }
        }

        Box(
            modifier
                .fillMaxSize()
                .background(HsColors.Video)
                .onPreviewKeyEvent { event ->
                    handleKey(event, chrome, actions, blocking, swallowUp)
                }
                .focusRequester(root)
                .focusable()
                // Tout toucher (commandes comprises) relance le masquage automatique.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.changedToDownIgnoreConsumed() }) chrome.poke()
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Pendant le cumul, chaque toucher d'un côté ajoute un pas
                    // tout de suite, sans attendre le délai du double tap (un
                    // tap rapide ne doit être ni perdu ni compté en double).
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val side = sideOf(down.position.x, size.width)
                        if (seeking.value && side != null) {
                            down.consume()
                            tapNudge(side, down.position)
                            waitForUpOrCancellation(pass = PointerEventPass.Initial)?.consume()
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Double tap sur un côté : recul (gauche) ou avance (droite).
                    detectTapGestures(
                        onDoubleTap = { offset -> sideOf(offset.x, size.width)?.let { tapNudge(it, offset) } },
                        onTap = { if (chrome.visible) chrome.hide() else chrome.show() },
                    )
                }
                .then(
                    if (levels == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(levels) {
                            var kind: LevelKind? = null
                            detectVerticalDragGestures(
                                onDragStart = { start ->
                                    // Bords haut et bas laissés au système (barres masquées).
                                    kind = when {
                                        start.y < size.height * 0.12f || start.y > size.height * 0.88f -> null
                                        start.x > size.width / 2f -> LevelKind.Volume
                                        else -> LevelKind.Brightness
                                    }
                                    kind?.let {
                                        level = if (it == LevelKind.Volume) levels.volume else levels.brightness
                                        adjusting = it
                                    }
                                },
                                onVerticalDrag = { change, dy ->
                                    val current = kind ?: return@detectVerticalDragGestures
                                    change.consume()
                                    // Les trois quarts de la hauteur : de 0 à 100 %.
                                    level = (level - dy / (size.height * 0.75f)).coerceIn(0f, 1f)
                                    if (current == LevelKind.Volume) levels.volume = level else levels.brightness = level
                                },
                                onDragEnd = {
                                    kind = null
                                    adjusting = null
                                },
                                onDragCancel = {
                                    kind = null
                                    adjusting = null
                                },
                            )
                        }
                    },
                ),
        ) {
            video()
            flash?.let { SeekRipple(it) }
            SeekPill(state.pendingSeekMs)
            LevelIndicator(adjusting, level)
            if (!failed && (state.resolving || (status.buffering && status.reconnectAttempt == 0))) {
                Text(
                    text = stringResource(R.string.loading),
                    style = Hs.type.body,
                    color = HsColors.OnMedia,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (!failed && status.reconnectAttempt > 0) {
                Text(
                    text = stringResource(R.string.playback_reconnecting),
                    style = Hs.type.label,
                    color = HsColors.OnMedia,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Hs.dimens.screenVertical)
                        .clip(RoundedCornerShape(Hs.dimens.radius))
                        .background(HsColors.MediaScrim)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            AnimatedVisibility(
                visible = controlsShown,
                enter = fadeIn(tween(FADE_MS)),
                exit = fadeOut(tween(FADE_MS)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(state, status, position, chrome, actions, focus)
            }
            val menu = chrome.menu
            if (menu != null && controlsShown) {
                TrackMenuPanel(
                    menu = menu,
                    tracks = state.tracks,
                    onSelect = { key ->
                        actions.selectTrack(key)
                        chrome.closeMenu()
                    },
                    onDisableSubtitles = {
                        actions.disableSubtitles()
                        chrome.closeMenu()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            val next = state.next
            if (next != null && !failed) {
                NextEpisodeCard(
                    next = next,
                    onPlayNow = actions::playNext,
                    onCancel = actions::cancelNext,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(horizontal = Hs.dimens.screenHorizontal, vertical = Hs.dimens.screenVertical),
                )
            }
            if (failed) {
                FailureCard(
                    message = state.error?.resolve(),
                    onRetry = actions::retry,
                    onBack = actions::exit,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private const val FADE_MS = 150

/** Tiers gauche : recul ; tiers droit : avance ; milieu : rien. */
private fun sideOf(x: Float, width: Int): Boolean? = when {
    x < width / 3f -> false
    x > width * 2 / 3f -> true
    else -> null
}

/** Tap d'avance ou de recul au doigt ; [id] relance l'onde à chaque tap. */
private class TapFlash(val forward: Boolean, val at: Offset, val id: Int)

/**
 * Onde partie du point touché, limitée au côté du tap, avec la flèche du
 * saut : le geste est vu tout de suite, même avant que le saut ne parte.
 */
@Composable
private fun BoxScope.SeekRipple(flash: TapFlash) {
    key(flash.id) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, tween(RIPPLE_MS, easing = LinearOutSlowInEasing)) }
        val fade = 1f - progress.value
        Canvas(Modifier.fillMaxSize()) {
            val half = size.width / 2f
            clipRect(left = if (flash.forward) half else 0f, right = if (flash.forward) size.width else half) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f * fade),
                    radius = size.height * (0.15f + 0.6f * progress.value),
                    center = flash.at,
                )
            }
        }
        Icon(
            if (flash.forward) Lucide.RotateCw else Lucide.RotateCcw,
            contentDescription = null,
            tint = HsColors.OnMedia.copy(alpha = fade),
            modifier = Modifier
                .align(if (flash.forward) BiasAlignment(0.75f, 0f) else BiasAlignment(-0.75f, 0f))
                .size(40.dp),
        )
    }
}

/**
 * « +1:30 » ou « −0:20 » : saut cumulé, appliqué à la fin des appuis. Apparaît
 * en fondu, grossit un instant à chaque appui ; reste du côté du saut.
 */
@Composable
private fun BoxScope.SeekPill(offsetMs: Long?) {
    // Dernière valeur gardée pendant le fondu de sortie.
    var shown by remember { mutableLongStateOf(0L) }
    if (offsetMs != null) shown = offsetMs
    val bump = remember { Animatable(1f) }
    LaunchedEffect(offsetMs) {
        if (offsetMs == null) return@LaunchedEffect
        bump.snapTo(1.12f)
        bump.animateTo(1f, tween(BUMP_MS))
    }
    AnimatedVisibility(
        visible = offsetMs != null,
        enter = fadeIn(tween(FADE_MS)) + scaleIn(tween(FADE_MS), initialScale = 0.85f),
        exit = fadeOut(tween(FADE_MS)),
        modifier = Modifier.align(if (shown >= 0) BiasAlignment(0.6f, 0f) else BiasAlignment(-0.6f, 0f)),
    ) {
        val sign = if (shown >= 0) "+" else "\u2212"
        Text(
            text = sign + Formats.clock(kotlin.math.abs(shown)),
            style = Hs.type.title.tabular(),
            color = HsColors.OnMedia,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = bump.value
                    scaleY = bump.value
                }
                .clip(RoundedCornerShape(Hs.dimens.radius))
                .background(HsColors.MediaScrim)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** Réglage en cours au doigt. */
private enum class LevelKind { Volume, Brightness }

/**
 * Volume ou luminosité pendant le glissement : icône, jauge et pourcentage,
 * au centre ; s'efface peu après la fin du geste.
 */
@Composable
private fun BoxScope.LevelIndicator(kind: LevelKind?, level: Float) {
    // Dernier réglage gardé pendant le fondu de sortie.
    var shown by remember { mutableStateOf(LevelKind.Volume) }
    if (kind != null) shown = kind
    AnimatedVisibility(
        visible = kind != null,
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS, delayMillis = LEVEL_LINGER_MS)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(Hs.dimens.radius))
                .background(HsColors.MediaScrim)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                if (shown == LevelKind.Volume) Lucide.Volume2 else Lucide.Sun,
                contentDescription = null,
                tint = HsColors.OnMedia,
                modifier = Modifier.size(Hs.dimens.icon),
            )
            Box(
                Modifier
                    .width(120.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(HsColors.OnMedia.copy(alpha = 0.3f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(level)
                        .fillMaxHeight()
                        .background(HsColors.OnMedia),
                )
            }
            Text(
                text = "${(level * 100).roundToInt()} %",
                style = Hs.type.label.tabular(),
                color = HsColors.OnMedia,
                modifier = Modifier.widthIn(min = 44.dp),
            )
        }
    }
}

private const val LEVEL_LINGER_MS = 500
private const val RIPPLE_MS = 450
private const val BUMP_MS = 160

/** Commandes qui prennent le focus selon la touche qui les a montrées. */
@Stable
private class ControlFocusRequesters {
    val playPause = FocusRequester()
    val seek = FocusRequester()
    val audio = FocusRequester()
    val subtitles = FocusRequester()

    fun of(focus: ControlFocus): FocusRequester = when (focus) {
        ControlFocus.PlayPause -> playPause
        ControlFocus.Seek -> seek
        ControlFocus.Audio -> audio
        ControlFocus.Subtitles -> subtitles
    }
}

/** Touches de la télécommande, vues avant les commandes. */
private fun handleKey(
    event: KeyEvent,
    chrome: PlayerChrome,
    actions: PlayerActions,
    blocking: Boolean,
    swallowUp: Array<Key?>,
): Boolean {
    val down = event.type == KeyEventType.KeyDown
    if (!down && event.type != KeyEventType.KeyUp) return false
    if (!down && swallowUp[0] == event.key) {
        swallowUp[0] = null
        return true
    }
    if (down) chrome.poke()
    if (blocking) return false
    when (event.key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
            if (down) {
                actions.togglePause()
                chrome.show()
            }
            return true
        }
        Key.MediaFastForward -> {
            if (down) actions.nudge(forward = true)
            return true
        }
        Key.MediaRewind -> {
            if (down) actions.nudge(forward = false)
            return true
        }
    }
    if (chrome.visible || !down) return false
    return when (event.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            actions.togglePause()
            chrome.show(ControlFocus.PlayPause)
            swallowUp[0] = event.key
            true
        }
        Key.DirectionLeft, Key.DirectionRight -> {
            actions.nudge(forward = event.key == Key.DirectionRight)
            chrome.show(ControlFocus.Seek)
            swallowUp[0] = event.key
            true
        }
        Key.DirectionUp, Key.DirectionDown -> {
            chrome.show(ControlFocus.PlayPause)
            swallowUp[0] = event.key
            true
        }
        else -> false
    }
}

/** Fond des commandes : transparent en haut, noir à 80 % en bas. */
private val ControlsScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.45f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.8f),
)

@Composable
private fun Controls(
    state: PlayerUiState,
    status: PlaybackStatus,
    position: PlaybackPosition,
    chrome: PlayerChrome,
    actions: PlayerActions,
    focus: ControlFocusRequesters,
) {
    val dimens = Hs.dimens
    Box(Modifier.fillMaxSize()) {
        if (!dimens.isTv) {
            HsIconButton(
                icon = Lucide.ArrowLeft,
                label = stringResource(R.string.back),
                onClick = actions::exit,
                style = ButtonStyle.Secondary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Téléphone en paysage : rien sous l'encoche de la caméra.
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = dimens.screenHorizontal, vertical = dimens.screenVertical),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Dégradé fixe posé sur l'image : pas de bord net au-dessus du titre.
                .background(ControlsScrim)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(
                    start = dimens.screenHorizontal,
                    end = dimens.screenHorizontal,
                    top = if (dimens.isTv) 36.dp else 40.dp,
                    bottom = if (dimens.isTv) 12.dp else 10.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 6.dp else 8.dp),
        ) {
            TitleBlock(state)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
                TimeText(position.positionMs)
                SeekBar(
                    position = position,
                    onNudge = actions::nudge,
                    onSeekTo = actions::seekTo,
                    onToggle = actions::togglePause,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus.seek)
                        .focusProperties { down = focus.playPause },
                )
                TimeText(position.durationMs.takeIf { it > 0 })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
                HsIconButton(
                    icon = Lucide.RotateCcw,
                    caption = (BACK_STEP_MS / 1000).toString(),
                    label = stringResource(R.string.player_rewind),
                    onClick = { actions.nudge(forward = false) },
                    modifier = Modifier.focusProperties { up = focus.seek },
                )
                HsIconButton(
                    icon = if (status.playing) Lucide.Pause else Lucide.Play,
                    label = stringResource(if (status.playing) R.string.player_pause else R.string.play),
                    onClick = actions::togglePause,
                    style = ButtonStyle.Primary,
                    modifier = Modifier
                        .focusRequester(focus.playPause)
                        .focusProperties { up = focus.seek },
                )
                HsIconButton(
                    icon = Lucide.RotateCw,
                    caption = (FORWARD_STEP_MS / 1000).toString(),
                    label = stringResource(R.string.player_forward),
                    onClick = { actions.nudge(forward = true) },
                    modifier = Modifier.focusProperties { up = focus.seek },
                )
                Spacer(Modifier.weight(1f))
                if (state.tracks.audio.size > 1) {
                    HsButton(
                        text = stringResource(R.string.player_audio),
                        onClick = { chrome.openMenu(TrackMenu.Audio) },
                        icon = Lucide.AudioLines,
                        modifier = Modifier
                            .focusRequester(focus.audio)
                            .focusProperties { up = focus.seek },
                    )
                }
                if (state.tracks.text.isNotEmpty()) {
                    HsButton(
                        text = stringResource(R.string.player_subtitles),
                        onClick = { chrome.openMenu(TrackMenu.Subtitles) },
                        icon = Lucide.Captions,
                        modifier = Modifier
                            .focusRequester(focus.subtitles)
                            .focusProperties { up = focus.seek },
                    )
                }
            }
        }
    }
}

/** Titre du film, ou nom de la série et « S1 · E3 · Titre ». */
@Composable
private fun TitleBlock(state: PlayerUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (state.title.isNotEmpty()) {
            Text(
                text = state.title,
                style = Hs.type.title,
                color = HsColors.OnMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.episode?.let { episode ->
            Text(
                text = episodeText(episode),
                style = Hs.type.body,
                color = Hs.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun episodeText(episode: EpisodeLabel): String {
    val title = episode.title
    return if (title != null) {
        stringResource(R.string.player_episode_title, episode.season, episode.number, title)
    } else {
        stringResource(R.string.player_episode, episode.season, episode.number)
    }
}

/** « 1:07:20 » ; rien si inconnu (la place reste réservée). */
@Composable
private fun TimeText(ms: Long?) {
    Text(
        text = ms?.let { Formats.clock(it) } ?: "",
        style = Hs.type.small.tabular(),
        color = HsColors.OnMedia,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = if (Hs.isTv) 52.dp else 64.dp),
    )
}

/**
 * Barre de progression : gauche / droite avancent (30 s) ou reculent (10 s) quand
 * elle a le focus ; au doigt, toucher ou glisser y place la lecture.
 */
@Composable
private fun SeekBar(
    position: PlaybackPosition,
    onNudge: (Boolean) -> Unit,
    onSeekTo: (Float) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val fraction = Formats.fraction(position.positionMs, position.durationMs)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction
    val label = stringResource(R.string.player_position)
    HsSurface(
        onClick = onToggle,
        focusScale = FocusScale.NONE,
        modifier = modifier
            .heightIn(min = dimens.minTouch)
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) onNudge(event.key == Key.DirectionRight)
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        contentPadding = PaddingValues(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (dimens.isTv) 24.dp else 40.dp)
                .pointerInput(position.durationMs) {
                    if (position.durationMs <= 0) return@pointerInput
                    detectTapGestures { offset -> onSeekTo(offset.x / size.width) }
                }
                .pointerInput(position.durationMs) {
                    if (position.durationMs <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let(onSeekTo)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ ->
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // Sur le bandeau sombre : rail Surface 2 bien visible (thème sombre forcé).
            ProgressRail(shown)
            // Curseur au bout de la partie lue.
            Box(Modifier.fillMaxWidth(shown), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .requiredSize(if (dimens.isTv) 10.dp else 14.dp)
                        .clip(CircleShape)
                        .background(HsColors.OnMedia),
                )
            }
        }
    }
}

/** Pistes audio ou sous-titres (« Désactivés » en tête) ; la piste en cours a le focus. */
@Composable
private fun TrackMenuPanel(
    menu: TrackMenu,
    tracks: TrackLists,
    onSelect: (TrackKey) -> Unit,
    onDisableSubtitles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val locale = currentLocale()
    val selected = remember { FocusRequester() }
    LaunchedEffect(menu) {
        withFrameNanos { }
        runCatching { selected.requestFocus() }
    }
    val options = if (menu == TrackMenu.Audio) tracks.audio else tracks.text
    val offSelected = menu == TrackMenu.Subtitles && tracks.textOff
    Column(
        modifier
            // Le focus reste dans la liste ; Retour la ferme.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .fillMaxHeight()
            .background(Hs.colors.surface)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
            .width(if (dimens.isTv) 300.dp else 340.dp)
            .padding(vertical = dimens.screenVertical, horizontal = dimens.gap),
    ) {
        PanelLabel(stringResource(if (menu == TrackMenu.Audio) R.string.player_audio else R.string.player_subtitles))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (menu == TrackMenu.Subtitles) {
                item(key = "off", contentType = "track") {
                    TrackRow(
                        text = stringResource(R.string.player_subtitles_off),
                        selected = offSelected,
                        onClick = onDisableSubtitles,
                        modifier = if (offSelected) Modifier.focusRequester(selected) else Modifier,
                    )
                }
            }
            itemsIndexed(options, key = { _, option -> "${option.key.group}-${option.key.track}" }, contentType = { _, _ -> "track" }) { index, option ->
                // Aucune piste marquée choisie : la première prend le focus.
                val takesFocus = option.selected || (!offSelected && index == 0 && options.none { it.selected })
                TrackRow(
                    text = trackDescription(option, locale) ?: stringResource(R.string.player_track, option.number),
                    selected = option.selected,
                    onClick = { onSelect(option.key) },
                    modifier = if (takesFocus) Modifier.focusRequester(selected) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun TrackRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    HsSurface(
        onClick = onClick,
        selected = selected,
        focusScale = FocusScale.NONE,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.categoryRowHeight),
        contentPadding = PaddingValues(horizontal = if (dimens.isTv) 12.dp else 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = Hs.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Lucide.Check, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(dimens.icon))
            }
        }
    }
}

/** « Épisode suivant dans 7 s » : « Lire maintenant » (focus) ou « Annuler ». */
@Composable
private fun NextEpisodeCard(next: NextEpisodeUi, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val playNow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { playNow.requestFocus() }
    }
    Column(
        modifier
            .widthIn(max = if (dimens.isTv) 380.dp else 420.dp)
            .clip(RoundedCornerShape(dimens.radius))
            .background(Hs.colors.surface)
            .padding(dimens.gapLarge),
        verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 6.dp else 8.dp),
    ) {
        Text(
            text = stringResource(R.string.player_next_in, next.secondsLeft),
            style = Hs.type.itemTitle.tabular(),
            maxLines = 1,
        )
        Text(
            text = episodeText(next.label),
            style = Hs.type.body,
            color = Hs.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressRail(
            fraction = next.secondsLeft.toFloat() / NEXT_EPISODE_SECONDS,
            modifier = Modifier.padding(vertical = if (dimens.isTv) 6.dp else 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
            HsButton(
                text = stringResource(R.string.player_play_now),
                onClick = onPlayNow,
                icon = Lucide.SkipForward,
                style = ButtonStyle.Primary,
                modifier = Modifier.focusRequester(playNow),
            )
            HsButton(text = stringResource(R.string.cancel), onClick = onCancel)
        }
    }
}

/** Flux impossible à lire (après les reconnexions) ou à préparer : « Réessayer » (focus) ou « Retour ». */
@Composable
private fun FailureCard(message: String?, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { retry.requestFocus() }
    }
    Column(
        modifier
            .padding(dimens.gapLarge)
            .widthIn(max = 440.dp)
            .clip(RoundedCornerShape(dimens.radius))
            .background(Hs.colors.surface)
            .padding(dimens.gapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Lucide.CircleAlert, contentDescription = null, tint = HsColors.Alert, modifier = Modifier.size(32.dp))
        Text(
            text = stringResource(if (message != null) R.string.player_error_title else R.string.playback_failed),
            style = Hs.type.itemTitle,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(message, style = Hs.type.body, color = Hs.colors.textSecondary, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap), modifier = Modifier.padding(top = 4.dp)) {
            HsButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                icon = Lucide.RefreshCw,
                style = ButtonStyle.Primary,
                modifier = Modifier.focusRequester(retry),
            )
            HsButton(text = stringResource(R.string.back), onClick = onBack, icon = Lucide.ArrowLeft)
        }
    }
}

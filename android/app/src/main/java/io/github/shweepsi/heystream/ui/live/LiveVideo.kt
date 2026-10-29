package io.github.shweepsi.heystream.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.ChannelLogo
import io.github.shweepsi.heystream.ui.components.FocusScale
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.LiveBadge
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide

/** Texte secondaire sur la vidéo, hors thème. */
private val OnMediaSecondary = HsColors.OnMedia.copy(alpha = 0.78f)

/** Rail d'une barre de progression sur le bandeau (fond noir à 60 %). */
private val OnMediaTrack = HsColors.OnMedia.copy(alpha = 0.3f)

/**
 * Le lecteur et ce qui s'affiche dessus (badge « EN DIRECT », reconnexion,
 * échec, bandeau du plein écran). [video] est toujours le premier enfant :
 * passer en plein écran ne change que [modifier] (taille et place).
 *
 * Au doigt : toucher l'aperçu passe en plein écran, toucher le plein écran
 * montre ou cache le bandeau. À la télécommande, le lecteur ne prend jamais
 * le focus (OK sur la chaîne en aperçu passe en plein écran).
 */
@Composable
internal fun LiveVideo(
    state: LiveUiState,
    nowSecs: Long,
    actions: LiveActions,
    video: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    val fullscreen = state.fullscreen
    val preview = state.preview
    val shape = if (fullscreen) RectangleShape else RoundedCornerShape(dimens.radius)
    HsSurface(
        onClick = actions::onVideoClick,
        shape = shape,
        color = HsColors.Video,
        contentColor = HsColors.OnMedia,
        focusScale = FocusScale.NONE,
        onClickLabel = if (fullscreen) null else stringResource(R.string.live_fullscreen),
        modifier = modifier
            .focusProperties { canFocus = false }
            .then(if (fullscreen || !isTv) Modifier else Modifier.border(1.dp, Hs.colors.surface2, shape)),
    ) {
        video(Modifier.fillMaxSize())
        val inset = if (isTv) 12.dp else 14.dp
        if (preview == null) {
            Icon(
                Lucide.Play,
                contentDescription = null,
                tint = HsColors.OnMedia.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(if (isTv) 36.dp else 48.dp),
            )
        } else if (!fullscreen) {
            LiveBadge(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(inset),
            )
        }
        PlaybackMessage(
            playback = state.playback,
            onRetry = actions::onRetryPlayback,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(inset),
        )
        if (fullscreen && state.overlayVisible && preview != null) {
            // Plein écran : barres du système masquées, mais pas l'encoche de la caméra.
            FullscreenInfo(
                preview = preview,
                nowSecs = nowSecs,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = dimens.screenHorizontal, vertical = dimens.screenVertical),
            )
            if (!isTv) {
                ExitFullscreenButton(
                    onClick = actions::onExitFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                )
            }
        }
    }
}

/** Reconnexion en cours, ou échec avec « Réessayer ». */
@Composable
private fun PlaybackMessage(playback: LivePlayback, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    when (playback) {
        LivePlayback.Reconnecting -> MediaText(stringResource(R.string.playback_reconnecting), modifier)
        is LivePlayback.Failed -> Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (Hs.isTv) 10.dp else 12.dp),
        ) {
            MediaText(playback.error?.resolve() ?: stringResource(R.string.playback_failed))
            HsButton(
                text = stringResource(R.string.retry),
                onClick = {
                    // Le bouton disparaît quand la lecture reprend : le focus revient aux chaînes.
                    focusManager.moveFocus(FocusDirection.Left)
                    onRetry()
                },
                icon = Lucide.RotateCw,
                style = ButtonStyle.Primary,
            )
        }
        LivePlayback.Idle, LivePlayback.Playing -> Unit
    }
}

@Composable
private fun MediaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Hs.type.body,
        color = HsColors.OnMedia,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(Hs.dimens.badgeRadius))
            .background(HsColors.MediaScrim)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Bandeau du plein écran : chaîne, programme en cours et sa progression. */
@Composable
private fun FullscreenInfo(preview: LivePreview, nowSecs: Long, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val locale = currentLocale()
    Row(
        modifier
            .widthIn(max = if (dimens.isTv) 520.dp else 560.dp)
            .clip(RoundedCornerShape(dimens.radius))
            .background(HsColors.MediaScrim)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(preview.logo, preview.name)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val number = preview.number
            Text(
                text = if (number != null) stringResource(R.string.live_joined, number.toString(), preview.name) else preview.name,
                style = Hs.type.itemTitle,
                color = HsColors.OnMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val now = preview.now
            if (now != null) {
                val range = stringResource(
                    R.string.live_time_range,
                    Formats.timeOfSeconds(now.start, locale),
                    Formats.timeOfSeconds(now.stop, locale),
                )
                Text(
                    text = stringResource(R.string.live_joined, range, now.title),
                    style = Hs.type.small,
                    color = OnMediaSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BannerRail(
                    fraction = Formats.fraction(nowSecs - now.start, now.stop - now.start),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(if (dimens.isTv) 240.dp else 280.dp),
                )
            }
        }
    }
}

/**
 * Progression du programme sur le bandeau. Le rail « sur image » (noir à 60 %)
 * disparaîtrait sur le fond du bandeau, lui-même noir à 60 % : rail blanc
 * translucide, barre `#F4F4F5`.
 */
@Composable
private fun BannerRail(fraction: Float, modifier: Modifier = Modifier) {
    val height = Hs.dimens.progressHeight
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(OnMediaTrack),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(shape)
                .background(HsColors.OnMedia),
        )
    }
}

/** Au doigt, en plein écran : retour à l'aperçu. */
@Composable
private fun ExitFullscreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.live_exit_fullscreen)
    HsSurface(
        onClick = onClick,
        shape = CircleShape,
        color = HsColors.MediaScrim,
        contentColor = HsColors.OnMedia,
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(Hs.dimens.minTouch)
            .semantics { contentDescription = label },
    ) {
        Icon(Lucide.ChevronLeft, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(Hs.dimens.icon))
    }
}

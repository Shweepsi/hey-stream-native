package io.github.shweepsi.heystream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide

/**
 * Image du fournisseur (affiche, logo). Rien n'est affiché tant qu'elle n'est
 * pas chargée, ni si elle manque : le fond [placeholder] reste visible.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = Hs.colors.surface2,
) {
    Box(modifier.background(placeholder)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Logo de chaîne, sur un petit fond Surface 2 ; ses initiales s'il manque. */
@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    Box(
        modifier
            .size(dimens.channelLogoWidth, dimens.channelLogoHeight)
            .clip(RoundedCornerShape(dimens.badgeRadius))
            .background(Hs.colors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text(
                text = initials(name),
                style = Hs.type.badge,
                color = Hs.colors.textSecondary,
                maxLines = 1,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
            )
        }
    }
}

/** Deux initiales au plus (« Info Monde » → « IM »). */
fun initials(name: String): String =
    name.split(' ', '-', '_', '.', '|', ':')
        .filter { word -> word.firstOrNull()?.isLetterOrDigit() == true }
        .take(2)
        .joinToString("") { it.first().uppercase() }

/**
 * Barre de progression. Sur une image ou une vidéo ([onMedia]) : barre
 * `#F4F4F5` sur rail noir à 60 %, hors thème ; ailleurs : Texte sur Surface 2.
 */
@Composable
fun ProgressRail(fraction: Float, modifier: Modifier = Modifier, onMedia: Boolean = false) {
    val height = Hs.dimens.progressHeight
    val shape = RoundedCornerShape(height / 2)
    val rail = if (onMedia) HsColors.MediaScrim else Hs.colors.surface2
    val bar = if (onMedia) HsColors.OnMedia else Hs.colors.text
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(rail),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(shape)
                .background(bar),
        )
    }
}

/** Pastille « vu » : icône `check` sur fond noir à 60 %, hors thème. */
@Composable
fun WatchedBadge(modifier: Modifier = Modifier, size: Dp = if (Hs.isTv) 22.dp else 28.dp) {
    val label = stringResource(R.string.watched)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(HsColors.MediaScrim)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.Check, contentDescription = null, tint = HsColors.OnMedia, modifier = Modifier.size(size * 0.62f))
    }
}

/** Badge « EN DIRECT » sur l'aperçu vidéo, hors thème. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.live_badge),
        style = Hs.type.badge,
        color = HsColors.OnMediaText,
        modifier = modifier
            .clip(RoundedCornerShape(Hs.dimens.badgeRadius))
            .background(HsColors.OnMedia)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * Affiche 2:3 avec le titre dessous (films, séries). Barre de progression si
 * commencée, pastille si vue (pas de barre sur un titre vu, sauf revisionnage).
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    watched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val dimens = Hs.dimens
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp)) {
        HsSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            focusScale = FocusScale.POSTER,
            color = Hs.colors.surface2,
            onClickLabel = title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused },
        ) {
            RemoteImage(imageUrl, Modifier.fillMaxSize())
            if (watched) {
                WatchedBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(if (dimens.isTv) 6.dp else 8.dp),
                )
            }
            if (progress != null) {
                ProgressRail(
                    fraction = progress,
                    onMedia = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(if (dimens.isTv) 8.dp else 10.dp),
                )
            }
        }
        Text(
            text = title,
            style = if (dimens.isTv) Hs.type.small else Hs.type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            // Titre complet sous l'affiche focalisée.
            modifier = Modifier.marqueeWhen(focused),
        )
    }
}

package io.github.shweepsi.heystream.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsIconButton
import io.github.shweepsi.heystream.ui.components.HsTopBar
import io.github.shweepsi.heystream.ui.components.ProgressRail
import io.github.shweepsi.heystream.ui.components.RemoteImage
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular

/** En dessous, le résumé est ramené à 2 lignes pour que les boutons restent visibles. */
private val COMPACT_HEIGHT = 400.dp

/** Marges d'une fiche : maquette TV (64 × 80 px) ramenée un peu pour les boutons. */
internal object DetailLayout {
    val tvHorizontal = 48.dp
    val tvVertical = 36.dp
    val tvGap = 36.dp
}

/**
 * Cadre d'une fiche : barre du haut au doigt (retour, section), rien sur
 * TV (la touche Retour suffit). Le contenu évite les barres système.
 */
@Composable
internal fun DetailFrame(sectionTitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Hs.colors.background),
    ) {
        if (!Hs.isTv) HsTopBar(title = sectionTitle, onBack = onBack)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            content()
        }
    }
}

/**
 * Affiche à gauche, contenu défilant à droite. L'affiche se réduit sur un
 * écran bas (téléphone en paysage) pour rester entière.
 */
@Composable
internal fun PosterLayout(
    image: String?,
    fallbackIcon: ImageVector,
    body: @Composable (modifier: Modifier, contentPadding: PaddingValues, compact: Boolean) -> Unit,
) {
    val dimens = Hs.dimens
    val horizontal = if (dimens.isTv) DetailLayout.tvHorizontal else dimens.screenHorizontal
    val vertical = if (dimens.isTv) DetailLayout.tvVertical else dimens.screenVertical
    val gap = if (dimens.isTv) DetailLayout.tvGap else dimens.gapLarge
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val byHeight: Dp = (maxHeight - vertical * 2) * (2f / 3f)
        val posterWidth = min(dimens.detailPosterWidth, byHeight).coerceAtLeast(96.dp)
        val compact = maxHeight < COMPACT_HEIGHT
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = horizontal),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            RemoteImage(
                url = image,
                fallback = fallbackIcon,
                modifier = Modifier
                    .padding(vertical = vertical)
                    .width(posterWidth)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(dimens.radius)),
            )
            body(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                PaddingValues(top = vertical, bottom = vertical, end = horizontal),
                // Écran bas (téléphone en paysage) : résumé raccourci, boutons visibles.
                compact,
            )
        }
    }
}

/** Titre, ligne d'infos (seulement les champs fournis) et résumé (4 lignes au plus). */
@Composable
internal fun DetailHeaderBlock(header: DetailHeader, modifier: Modifier = Modifier, compact: Boolean = false) {
    val dimens = Hs.dimens
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 9.dp else 10.dp)) {
        Text(
            text = header.title,
            style = Hs.type.headline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val parts = metaParts(header)
        if (parts.isNotEmpty()) MetaLine(parts)
        val plot = header.plot
        if (plot != null) {
            Text(
                text = plot,
                style = Hs.type.bodyLarge,
                maxLines = if (compact) 2 else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = if (dimens.isTv) 9.dp else if (compact) 0.dp else 6.dp)
                    .widthIn(max = 640.dp),
            )
        }
    }
}

/** « 2025 · 1 h 52 · ★ 7.2 » ; les points ne sont pas lus par l'accessibilité. */
@Composable
private fun MetaLine(parts: List<MetaPart>) {
    val color = Hs.colors.textSecondary
    val style = Hs.type.body.tabular()
    Row(horizontalArrangement = Arrangement.spacedBy(if (Hs.isTv) 9.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text("·", style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
            }
            when (part) {
                is MetaPart.Year -> Text(part.text, style = style, color = color, maxLines = 1)
                is MetaPart.Duration -> Text(durationText(part.hours, part.minutes), style = style, color = color, maxLines = 1)
                is MetaPart.Rating -> {
                    val label = stringResource(R.string.detail_rating, part.text)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
                    ) {
                        Icon(Lucide.Star, contentDescription = null, tint = color, modifier = Modifier.size(Hs.dimens.icon * 0.85f))
                        Text(part.text, style = style, color = color, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** « 1 h 52 », « 45 min ». */
@Composable
internal fun durationText(hours: Long, minutes: Long): String =
    if (hours > 0) stringResource(R.string.duration_hm, hours, minutes) else stringResource(R.string.duration_m, minutes)

@Composable
internal fun durationText(seconds: Long): String {
    val (hours, minutes) = Formats.hoursMinutes(seconds)
    return durationText(hours, minutes)
}

/** « Vu jusqu’à 1:07:20 » et « 45 min restantes » au-dessus de la barre. */
@Composable
internal fun ProgressBlock(progress: WatchProgress, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    Column(
        modifier.widthIn(max = if (dimens.isTv) 315.dp else 380.dp),
        verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 6.dp else 8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.watched_until, Formats.clock(progress.positionMs)),
                style = Hs.type.small.tabular(),
                color = Hs.colors.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            progress.remainingMinutes?.let { minutes ->
                Text(
                    text = pluralStringResource(R.plurals.remaining_minutes, minutes.toInt(), minutes),
                    style = Hs.type.small.tabular(),
                    color = Hs.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        progress.fraction?.let { ProgressRail(it) }
    }
}

/** Favori : cœur au trait rouge quand le titre est dans les favoris. */
@Composable
internal fun FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HsIconButton(
        icon = Lucide.Heart,
        tint = if (favorite) HsColors.Favorite else null,
        label = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * « Marquer comme vu / non vu » (icône `check`). Sur un écran bas
 * ([compact], téléphone en paysage), icône seule, libellé lu par
 * l'accessibilité (docs/DESIGN.md) : tous les boutons tiennent sur une ligne,
 * visibles sans défiler. L'icône montre l'état, comme le cœur du favori.
 */
@Composable
internal fun WatchedButton(watched: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (compact) {
        HsIconButton(
            icon = if (watched) Lucide.CircleCheck else Lucide.Check,
            label = stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched),
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    HsButton(
        text = stringResource(if (watched) R.string.mark_unwatched else R.string.mark_watched),
        onClick = onClick,
        icon = Lucide.Check,
        style = ButtonStyle.Secondary,
        modifier = modifier,
    )
}

/** Erreur d'une fiche (rien à afficher) : message et « Réessayer », qui prend le focus. */
@Composable
internal fun DetailError(message: String, onRetry: () -> Unit) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Icon(Lucide.CircleAlert, contentDescription = null, tint = HsColors.Alert, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.detail_unavailable), style = Hs.type.itemTitle, textAlign = TextAlign.Center)
            Text(message, style = Hs.type.body, color = Hs.colors.textSecondary, textAlign = TextAlign.Center)
            HsButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                icon = Lucide.RefreshCw,
                modifier = Modifier.focusRequester(retry),
            )
        }
    }
}

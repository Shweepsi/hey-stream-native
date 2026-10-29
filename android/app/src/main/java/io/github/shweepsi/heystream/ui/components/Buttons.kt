package io.github.shweepsi.heystream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shweepsi.heystream.ui.theme.Hs

/** Importance d'un bouton. */
enum class ButtonStyle {
    /** Action principale : couleurs inversées (fond Texte), comme « Reprendre ». */
    Primary,

    /** Action secondaire : fond Surface 2. */
    Secondary,

    /** Sur une surface : fond Surface (« Rechercher », « Réglages » de l'accueil). */
    Surface,
}

@Composable
fun HsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.Secondary,
    enabled: Boolean = true,
) {
    val colors = Hs.colors
    val dimens = Hs.dimens
    val (background, content) = when (style) {
        ButtonStyle.Primary -> colors.text to colors.background
        ButtonStyle.Secondary -> colors.surface2 to colors.text
        ButtonStyle.Surface -> colors.surface to colors.text
    }
    HsSurface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = dimens.minTouch),
        enabled = enabled,
        color = background,
        contentColor = if (enabled) content else colors.textSecondary,
        contentPadding = PaddingValues(
            horizontal = if (dimens.isTv) 18.dp else 20.dp,
            vertical = if (dimens.isTv) 10.dp else 12.dp,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(dimens.icon), tint = LocalContentColor.current)
            }
            Text(text, style = Hs.type.label, color = LocalContentColor.current, maxLines = 1)
        }
    }
}

/** Bouton à icône seule ; [label] est lu par l'accessibilité. */
@Composable
fun HsIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Secondary,
    selected: Boolean = false,
    /** Petit texte au centre d'une icône plus grande (« 10 » dans la flèche du recul). */
    caption: String? = null,
) {
    val colors = Hs.colors
    val dimens = Hs.dimens
    val background = when (style) {
        ButtonStyle.Primary -> colors.text
        ButtonStyle.Secondary -> colors.surface2
        ButtonStyle.Surface -> colors.surface
    }
    HsSurface(
        onClick = onClick,
        modifier = modifier
            .size(if (dimens.isTv) 40.dp else 48.dp)
            .semantics { contentDescription = label },
        selected = selected,
        color = background,
        contentColor = if (style == ButtonStyle.Primary) colors.background else colors.text,
        contentAlignment = Alignment.Center,
    ) {
        if (caption == null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(dimens.icon), tint = LocalContentColor.current)
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(dimens.icon * 1.6f), tint = LocalContentColor.current)
                Text(
                    caption,
                    style = Hs.type.badge.copy(fontSize = if (dimens.isTv) 9.sp else 10.sp, letterSpacing = 0.sp),
                    color = LocalContentColor.current,
                    maxLines = 1,
                )
            }
        }
    }
}

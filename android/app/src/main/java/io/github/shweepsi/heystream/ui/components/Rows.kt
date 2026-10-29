package io.github.shweepsi.heystream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular
import java.text.NumberFormat

/** Intitulé d'un panneau (« CATÉGORIES »). */
@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = Hs.type.overline,
        color = Hs.colors.textSecondary,
        maxLines = 1,
        modifier = modifier.padding(start = if (Hs.isTv) 12.dp else 14.dp, bottom = 8.dp),
    )
}

/**
 * Ligne de catégorie : nom, nombre d'entrées (absent si inconnu), cadenas si
 * verrouillée par le contrôle parental. La catégorie ouverte est
 * [selected] (texte demi-gras).
 */
@Composable
fun CategoryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Long? = null,
    selected: Boolean = false,
    locked: Boolean = false,
    icon: ImageVector? = null,
) {
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(dimens.icon), tint = LocalContentColor.current)
            }
            Text(
                text = name,
                style = Hs.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .marqueeWhen(if (dimens.isTv) LocalSurfaceFocused.current else selected),
            )
            if (locked) {
                Icon(
                    Lucide.Lock,
                    contentDescription = stringResource(R.string.category_locked),
                    modifier = Modifier.size(dimens.icon * 0.9f),
                    tint = Hs.colors.textSecondary,
                )
            } else if (count != null) {
                Text(
                    text = NumberFormat.getIntegerInstance(currentLocale()).format(count),
                    style = Hs.type.small.tabular(),
                    color = Hs.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

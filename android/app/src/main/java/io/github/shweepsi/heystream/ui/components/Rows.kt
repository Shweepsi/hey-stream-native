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
import androidx.compose.ui.unit.sp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide

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
 * Ligne de catégorie : nom, cadenas si verrouillée par le contrôle parental.
 * La catégorie ouverte est [selected] (texte demi-gras). Pas de compteur : le
 * nombre de titres s'écrit une fois, dans l'en-tête de la liste. [dense] : texte
 * un peu plus petit à la TV (13 sp au lieu de 14), pour les listes de noms du
 * fournisseur, souvent longs ; sans effet au doigt.
 */
@Composable
fun CategoryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    locked: Boolean = false,
    icon: ImageVector? = null,
    dense: Boolean = false,
) {
    val dimens = Hs.dimens
    val textStyle = Hs.type.body.let { if (dense && dimens.isTv) it.copy(fontSize = 13.sp) else it }
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
                style = textStyle.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
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
            }
        }
    }
}

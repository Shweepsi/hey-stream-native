package io.github.shweepsi.heystream.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors

/** Agrandissement au focus (docs/DESIGN.md). */
object FocusScale {
    /** Tuiles, boutons, lignes. */
    const val DEFAULT = 1.04f

    /** Affiches. */
    const val POSTER = 1.06f

    /** Lignes de liste pleine largeur : pas d'agrandissement (déborderait du panneau). */
    const val NONE = 1f
}

/** Courbe « ease-out » des animations de focus. */
val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Durée des animations de focus (120–150 ms). */
const val FOCUS_ANIMATION_MS = 130

/**
 * Élément activable à la télécommande comme au doigt (docs/DESIGN.md).
 *
 * - **Focus** (télécommande, clavier) : contour bleu de 2 dp dessiné par-dessus
 *   les bords (rien ne bouge autour) et agrandissement [focusScale].
 * - **Sélectionné** ([selected]) : au doigt, couleurs inversées (fond Texte,
 *   texte Fond) ; à la télécommande, fond Surface 2.
 *
 * La couleur du contenu est fournie par `LocalContentColor`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HsSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Hs.dimens.radius),
    color: Color = Color.Transparent,
    contentColor: Color = Hs.colors.text,
    focusScale: Float = FocusScale.DEFAULT,
    contentPadding: PaddingValues = PaddingValues(),
    contentAlignment: Alignment = Alignment.TopStart,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    // État de focus lu directement (pas par les interactions) : un focus
    // demandé dès l'ouverture de l'écran arrive avant que ses écouteurs ne
    // soient prêts, et son contour manquerait.
    var focused by remember { mutableStateOf(false) }
    val isTv = Hs.isTv
    val colors = Hs.colors
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = tween(FOCUS_ANIMATION_MS, easing = EaseOut),
        label = "focusScale",
    )
    val border by animateColorAsState(
        targetValue = if (focused) HsColors.Focus else Color.Transparent,
        animationSpec = tween(FOCUS_ANIMATION_MS, easing = EaseOut),
        label = "focusBorder",
    )
    val background = when {
        selected && !isTv -> colors.text
        selected -> colors.surface2
        else -> color
    }
    val foreground = if (selected && !isTv) colors.background else contentColor
    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(Hs.dimens.focusBorder, border, shape)
            .clip(shape)
            .background(background)
            .semantics { this.selected = selected }
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = interaction,
                indication = if (isTv) null else ripple(),
                enabled = enabled,
                role = role,
                onClickLabel = onClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            content()
        }
    }
}

package io.github.shweepsi.heystream.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.shweepsi.heystream.R

/** Inter (licence OFL), embarquée. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Styles de texte. TV : maquettes 1280 × 720 ramenées à 960 × 540 dp
 * (× 0,75), bornées par le tableau de docs/DESIGN.md (12 sp au moins).
 */
@Immutable
data class HsType(
    /** Titres des tuiles de l'accueil. */
    val display: TextStyle,
    /** Titre d'une fiche. */
    val headline: TextStyle,
    /** Titre d'écran, heure de l'accueil. */
    val title: TextStyle,
    /** Titre d'un élément (chaîne, programme en cours). */
    val itemTitle: TextStyle,
    /** Texte courant. */
    val body: TextStyle,
    /** Résumé d'une fiche. */
    val bodyLarge: TextStyle,
    /** Libellé de bouton. */
    val label: TextStyle,
    /** Petit texte (minimum). */
    val small: TextStyle,
    /** Intitulé de panneau, en capitales espacées. */
    val overline: TextStyle,
    /** Badge sur une image (« EN DIRECT »). */
    val badge: TextStyle,
)

private fun style(size: TextUnit, weight: FontWeight, letterSpacing: TextUnit = 0.sp, lineHeight: Float = 1.3f) =
    TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size,
        letterSpacing = letterSpacing,
        lineHeight = size * lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

/** Chiffres tabulaires : heures, numéros de chaîne, durées. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

val TvType = HsType(
    display = style(30.sp, FontWeight.Bold, (-0.02).em, 1.15f),
    headline = style(36.sp, FontWeight.Bold, (-0.03).em, 1.1f),
    title = style(22.sp, FontWeight.SemiBold, (-0.02).em, 1.2f),
    itemTitle = style(14.sp, FontWeight.SemiBold),
    body = style(14.sp, FontWeight.Normal),
    bodyLarge = style(15.sp, FontWeight.Normal, lineHeight = 1.55f),
    label = style(14.sp, FontWeight.Medium),
    small = style(12.sp, FontWeight.Normal),
    overline = style(12.sp, FontWeight.SemiBold, 0.06.em),
    badge = style(12.sp, FontWeight.Bold, 0.06.em, 1.1f),
)

val TouchType = HsType(
    display = style(28.sp, FontWeight.Bold, (-0.02).em, 1.15f),
    headline = style(28.sp, FontWeight.Bold, (-0.03).em, 1.1f),
    title = style(22.sp, FontWeight.SemiBold, (-0.01).em, 1.2f),
    itemTitle = style(16.sp, FontWeight.SemiBold),
    body = style(16.sp, FontWeight.Normal),
    bodyLarge = style(17.sp, FontWeight.Normal, lineHeight = 1.5f),
    label = style(16.sp, FontWeight.Medium),
    small = style(13.sp, FontWeight.Normal),
    overline = style(13.sp, FontWeight.SemiBold, 0.06.em),
    // 13 sp au moins au doigt (docs/DESIGN.md).
    badge = style(13.sp, FontWeight.Bold, 0.06.em, 1.1f),
)

/** Pour les composants Material (champs, dialogues). */
fun HsType.toMaterial(): Typography = Typography(
    displayLarge = display,
    headlineMedium = headline,
    titleLarge = title,
    titleMedium = itemTitle,
    bodyLarge = body,
    bodyMedium = body,
    bodySmall = small,
    labelLarge = label,
    labelMedium = small,
    labelSmall = small,
)

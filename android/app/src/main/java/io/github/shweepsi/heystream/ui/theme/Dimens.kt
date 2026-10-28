package io.github.shweepsi.heystream.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mesures (docs/DESIGN.md). TV : maquettes × 0,75 (écran de 960 × 540 dp).
 * Tablette et téléphone : zones tactiles d'au moins 48 dp.
 */
@Immutable
data class HsDimens(
    val isTv: Boolean,
    /** Marges d'écran. */
    val screenHorizontal: Dp,
    val screenVertical: Dp,
    /** Espacements. */
    val gapSmall: Dp,
    val gap: Dp,
    val gapLarge: Dp,
    /** Coins des tuiles, affiches, boutons, panneaux. */
    val radius: Dp,
    /** Coins des petits badges (moitié). */
    val badgeRadius: Dp,
    /** Contour de focus. */
    val focusBorder: Dp,
    /** Hauteur minimale d'un élément activable. */
    val minTouch: Dp,
    /** Ligne de catégorie. */
    val categoryRowHeight: Dp,
    /** Ligne de chaîne (numéro, logo, nom, programme). */
    val channelRowHeight: Dp,
    /** Logo de chaîne (16:10 environ). */
    val channelLogoWidth: Dp,
    val channelLogoHeight: Dp,
    /** Largeur des panneaux du direct et des films / séries. */
    val categoriesPanelWidth: Dp,
    val channelsPanelWidth: Dp,
    /** Grille d'affiches : colonnes sur TV, largeur minimale ailleurs. */
    val posterColumns: Int,
    val posterMinWidth: Dp,
    val posterGapHorizontal: Dp,
    val posterGapVertical: Dp,
    /** Icônes. */
    val icon: Dp,
    val iconLarge: Dp,
    /** Barre de progression. */
    val progressHeight: Dp,
    /** Affiche d'une fiche. */
    val detailPosterWidth: Dp,
)

val TvDimens = HsDimens(
    isTv = true,
    screenHorizontal = 30.dp,
    screenVertical = 24.dp,
    gapSmall = 6.dp,
    gap = 12.dp,
    gapLarge = 24.dp,
    radius = 8.dp,
    badgeRadius = 4.dp,
    focusBorder = 2.dp,
    minTouch = 32.dp,
    categoryRowHeight = 34.dp,
    channelRowHeight = 50.dp,
    channelLogoWidth = 36.dp,
    channelLogoHeight = 24.dp,
    categoriesPanelWidth = 180.dp,
    channelsPanelWidth = 285.dp,
    posterColumns = 6,
    posterMinWidth = 110.dp,
    posterGapHorizontal = 15.dp,
    posterGapVertical = 21.dp,
    icon = 16.dp,
    iconLarge = 48.dp,
    progressHeight = 3.dp,
    detailPosterWidth = 255.dp,
)

val TouchDimens = HsDimens(
    isTv = false,
    screenHorizontal = 24.dp,
    screenVertical = 16.dp,
    gapSmall = 8.dp,
    gap = 16.dp,
    gapLarge = 24.dp,
    radius = 12.dp,
    badgeRadius = 6.dp,
    focusBorder = 2.dp,
    minTouch = 48.dp,
    categoryRowHeight = 48.dp,
    channelRowHeight = 64.dp,
    channelLogoWidth = 48.dp,
    channelLogoHeight = 32.dp,
    categoriesPanelWidth = 220.dp,
    channelsPanelWidth = 360.dp,
    posterColumns = 0,
    posterMinWidth = 120.dp,
    posterGapHorizontal = 16.dp,
    posterGapVertical = 20.dp,
    icon = 20.dp,
    iconLarge = 48.dp,
    progressHeight = 4.dp,
    detailPosterWidth = 240.dp,
)

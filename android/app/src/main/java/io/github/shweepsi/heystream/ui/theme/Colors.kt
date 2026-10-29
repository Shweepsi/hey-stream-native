package io.github.shweepsi.heystream.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Couleurs du thème (docs/DESIGN.md). Le bleu et le rouge sont réservés au
 * logo ; seule exception, le bleu du contour de focus.
 */
@Immutable
data class HsColors(
    val background: Color,
    /** Tuiles, panneaux. */
    val surface: Color,
    /** Puces, champs, boutons secondaires, élément sélectionné (TV). */
    val surface2: Color,
    val separator: Color,
    val text: Color,
    val textSecondary: Color,
    val isDark: Boolean,
) {
    companion object {
        val Blue = Color(0xFF1F6BFF)
        val Red = Color(0xFFFF2E3B)

        /** Contour de focus de la télécommande. */
        val Focus = Blue

        /**
         * Sur une image ou une vidéo, hors thème : badge « EN DIRECT », barre
         * de progression et coche « vu » d'une affiche.
         */
        val OnMedia = Color(0xFFF4F4F5)
        val OnMediaText = Color(0xFF121214)

        /** Noir à 60 % : rail de progression et pastille sur une image. */
        val MediaScrim = Color(0x99000000)

        /** Fond du lecteur vidéo. */
        val Video = Color(0xFF0B0B0D)
    }
}

val DarkColors = HsColors(
    background = Color(0xFF121214),
    surface = Color(0xFF1C1C1F),
    surface2 = Color(0xFF2A2A2E),
    separator = Color(0xFF26262A),
    text = Color(0xFFF4F4F5),
    textSecondary = Color(0xFFA1A1AA),
    isDark = true,
)

val LightColors = HsColors(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE9E9EC),
    separator = Color(0xFFE4E4E7),
    text = Color(0xFF18181B),
    textSecondary = Color(0xFF52525B),
    isDark = false,
)

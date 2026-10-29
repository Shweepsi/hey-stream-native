package io.github.shweepsi.heystream.ui.live

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/** Barre du haut (tactile, `HsTopBar`) et son séparateur. */
val LiveTopBarHeight = 64.dp + 1.dp

/**
 * Mesures des trois panneaux et place de l'aperçu vidéo dans la zone utile
 * (hors barres système). L'aperçu et le plein écran sont le même lecteur :
 * seule sa place change, calculée ici.
 */
@Immutable
data class LiveLayout(
    val categoriesWidth: Dp,
    val channelsWidth: Dp,
    /** Marges autour des panneaux (TV). */
    val marginHorizontal: Dp,
    val marginVertical: Dp,
    /** Espace entre les panneaux (TV). */
    val gap: Dp,
    /** Barre du haut (tactile), séparateur compris. */
    val topBar: Dp,
    /** Marge intérieure du panneau d'aperçu (tactile). */
    val previewPadding: Dp,
    /** Aperçu vidéo 16:9, depuis le coin de la zone utile. */
    val videoX: Dp,
    val videoY: Dp,
    val videoWidth: Dp,
    val videoHeight: Dp,
    /** Téléphone : panneaux étroits, textes plus compacts. */
    val compact: Boolean,
)

/**
 * TV : maquette 1280 × 720 ramenée à 960 × 540 dp (marges 30 / 24 dp,
 * 18 dp entre les panneaux). Tablette : barre du haut, puis trois colonnes
 * séparées par un trait. Téléphone (moins de 960 dp de large) : colonnes
 * proportionnelles, plus étroites.
 */
fun liveLayout(isTv: Boolean, width: Dp, height: Dp, categories: Dp, channels: Dp): LiveLayout {
    if (isTv) {
        val horizontal = 30.dp
        val vertical = 24.dp
        val gap = 18.dp
        val x = horizontal + categories + gap + channels + gap
        val (videoWidth, videoHeight) = video((width - x - horizontal).coerceAtLeast(0.dp), height - vertical)
        return LiveLayout(
            categoriesWidth = categories,
            channelsWidth = channels,
            marginHorizontal = horizontal,
            marginVertical = vertical,
            gap = gap,
            topBar = 0.dp,
            previewPadding = 0.dp,
            videoX = x,
            videoY = vertical,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            compact = false,
        )
    }
    val compact = width < 960.dp
    val categoriesWidth = if (compact) min(categories, width * 0.22f) else categories
    val channelsWidth = if (compact) min(channels, width * 0.34f) else channels
    val padding = if (compact) 12.dp else 24.dp
    val x = categoriesWidth + channelsWidth + padding
    val y = LiveTopBarHeight + padding
    val (videoWidth, videoHeight) = video((width - x - padding).coerceAtLeast(0.dp), height - y)
    return LiveLayout(
        categoriesWidth = categoriesWidth,
        channelsWidth = channelsWidth,
        marginHorizontal = 0.dp,
        marginVertical = 0.dp,
        gap = 0.dp,
        topBar = LiveTopBarHeight,
        previewPadding = padding,
        videoX = x,
        videoY = y,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        compact = compact,
    )
}

/** 16:9 dans la largeur, sans prendre plus de 70 % de la hauteur restante (place du programme). */
private fun video(width: Dp, heightBelow: Dp): Pair<Dp, Dp> {
    val maxHeight = (heightBelow * 0.7f).coerceAtLeast(0.dp)
    val height = width * 9f / 16f
    return if (height <= maxHeight) width to height else maxHeight * 16f / 9f to maxHeight
}

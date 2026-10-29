package io.github.shweepsi.heystream.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular
import kotlinx.coroutines.delay

/** Logo A : symbole, suivi du mot « hey stream » si [wordmark]. */
@Composable
fun Logo(modifier: Modifier = Modifier, size: Dp = 30.dp, wordmark: Boolean = true) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(size * 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = if (wordmark) null else stringResource(R.string.app_name),
            modifier = Modifier.size(size * 1.12f, size),
        )
        if (wordmark) {
            Text(
                text = stringResource(R.string.wordmark),
                style = Hs.type.title.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

/** Heure courante, mise à jour à chaque minute. */
@Composable
fun rememberNow(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            now.longValue = current
            delay(60_000 - current % 60_000)
        }
    }
    return now
}

/** Heure et date (accueil, en haut à droite). */
@Composable
fun Clock(modifier: Modifier = Modifier, showDate: Boolean = true) {
    val now by rememberNow()
    val locale = currentLocale()
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(Formats.time(now, locale), style = Hs.type.title.tabular(), maxLines = 1)
        if (showDate) {
            Text(Formats.dayAndDate(now, locale), style = Hs.type.small, color = Hs.colors.textSecondary, maxLines = 1)
        }
    }
}

/**
 * Barre du haut, au doigt seulement (tablette, téléphone) : retour, titre,
 * actions, sur le fond de l'écran (pas de bande grise). Sur TV, la touche
 * Retour de la télécommande suffit.
 *
 * Son fond va jusqu'aux bords de l'écran (sous la barre d'état et à côté de
 * l'encoche) ; son contenu reste dans la zone sûre. Le contenu en dessous
 * s'écarte des côtés et du bas avec [BelowTopBar].
 */
@Composable
fun HsTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Hs.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(Hs.dimens.topBarHeight)
            .padding(horizontal = Hs.dimens.screenHorizontal - 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            HsIconButton(Lucide.ChevronLeft, stringResource(R.string.back), onBack, style = ButtonStyle.Ghost)
        }
        Text(title, style = Hs.type.title, maxLines = 1, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Contenu sous [HsTopBar] : hors des barres système et de l'encoche, sur les côtés et en bas. */
val BelowTopBar: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/** Hors de l'encoche et des barres système, sur les côtés seulement. */
val SafeSides: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/** Chargement : rien de plus qu'un texte discret (pas d'animation coûteuse). */
@Composable
fun LoadingState(modifier: Modifier = Modifier, text: String = stringResource(R.string.loading)) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Hs.type.body, color = Hs.colors.textSecondary)
    }
}

/** Message centré (liste vide, erreur), avec une action facultative. */
@Composable
fun MessageState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Hs.colors.textSecondary, modifier = Modifier.size(32.dp))
            }
            Text(title, style = Hs.type.itemTitle, textAlign = TextAlign.Center)
            if (message != null) {
                Text(message, style = Hs.type.body, color = Hs.colors.textSecondary, textAlign = TextAlign.Center)
            }
            if (action != null && onAction != null) {
                HsButton(action, onAction, style = ButtonStyle.Secondary)
            }
        }
    }
}

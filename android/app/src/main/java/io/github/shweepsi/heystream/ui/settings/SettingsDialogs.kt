package io.github.shweepsi.heystream.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Confirmation de la déconnexion, qui efface les données de l'appareil. */
@Composable
fun SignOutDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        SignOutPanel(busy, onConfirm, onDismiss)
    }
}

/** Contenu de la confirmation (sans fenêtre), pour la boîte et les tests. */
@Composable
fun SignOutPanel(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val cancel = remember { FocusRequester() }
    // Annuler d'abord : rien n'est effacé par un appui de trop.
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    Column(
        modifier
            .widthIn(max = if (dimens.isTv) 420.dp else 460.dp)
            .background(Hs.colors.surface, RoundedCornerShape(dimens.radius))
            .padding(dimens.gapLarge),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        Text(
            stringResource(R.string.settings_sign_out_title),
            style = Hs.type.itemTitle,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.settings_sign_out_message), style = Hs.type.body, color = Hs.colors.textSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = dimens.gapSmall),
            horizontalArrangement = Arrangement.spacedBy(dimens.gap, androidx.compose.ui.Alignment.End),
        ) {
            HsButton(
                text = stringResource(R.string.cancel),
                onClick = { if (!busy) onDismiss() },
                modifier = Modifier.focusRequester(cancel),
            )
            HsButton(
                text = stringResource(if (busy) R.string.settings_signing_out else R.string.settings_sign_out),
                onClick = { if (!busy) onConfirm() },
                icon = Lucide.LogOut,
                style = ButtonStyle.Primary,
            )
        }
    }
}

/** Texte d'une licence (`assets/licenses/`), à faire défiler. */
@Composable
fun LicenseDialog(license: License, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(initialValue = null, license) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(license.asset).bufferedReader().use { it.readText() } }.getOrNull()
        } ?: ""
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LicensePanel(
            title = stringResource(license.title),
            subtitle = stringResource(license.licenseName),
            text = text,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Contenu de la licence. Le texte prend le focus : haut et bas le font
 * défiler, puis bas atteint « Fermer » une fois en fin de texte.
 */
@Composable
fun LicensePanel(title: String, subtitle: String, text: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val scroll = rememberScrollState()
    val textFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val step = with(LocalDensity.current) { (if (dimens.isTv) 120.dp else 160.dp).toPx() }
    // Télécommande seulement : au doigt, le texte défile sans focus ni contour.
    LaunchedEffect(text != null) { if (text != null && dimens.isTv) runCatching { textFocus.requestFocus() } }
    val shape = RoundedCornerShape(dimens.radius)
    Column(
        modifier
            .fillMaxWidth(if (dimens.isTv) 0.7f else 0.8f)
            .fillMaxHeight(0.86f)
            .background(Hs.colors.surface, shape)
            .padding(dimens.gapLarge),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        Column {
            Text(title, style = Hs.type.itemTitle, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = Hs.type.small, color = Hs.colors.textSecondary)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(dimens.focusBorder, if (focused) HsColors.Focus else Hs.colors.separator, shape)
                .onFocusChanged { focused = it.isFocused }
                .focusRequester(textFocus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        event.key == Key.DirectionDown && scroll.canScrollForward -> {
                            scroll.dispatchRawDelta(step)
                            true
                        }
                        event.key == Key.DirectionUp && scroll.canScrollBackward -> {
                            scroll.dispatchRawDelta(-step)
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .verticalScroll(scroll)
                .padding(if (dimens.isTv) 12.dp else 14.dp),
        ) {
            Text(
                text = when (text) {
                    null -> stringResource(R.string.loading)
                    "" -> stringResource(R.string.settings_license_unavailable)
                    else -> text
                },
                style = Hs.type.small,
                color = Hs.colors.text,
            )
        }
        HsButton(
            text = stringResource(R.string.close),
            onClick = onDismiss,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        )
    }
}

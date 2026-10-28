package io.github.shweepsi.heystream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.launch

/** Ce que le code PIN doit faire. */
enum class PinPurpose {
    /** Déverrouiller les catégories adultes pour la session. */
    Unlock,

    /** Aucun code encore : en créer un (puis déverrouiller). */
    Create,

    /** Réglages : changer le code (l'actuel d'abord). */
    Change,
}

private enum class PinStep { Current, New, Confirm }

const val PIN_LENGTH = 4

/**
 * Contrôle parental : saisie du code PIN à 4 chiffres, avec un pavé
 * utilisable à la télécommande. Les chiffres du clavier ou de la
 * télécommande marchent aussi. Les erreurs (code faux, trop d'essais)
 * viennent du cœur.
 */
@Composable
fun ParentalPinDialog(purpose: PinPurpose, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    val session = appGraph().session
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(if (purpose == PinPurpose.Change) PinStep.Current else PinStep.New) }
    var entered by remember { mutableStateOf("") }
    var current by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<UiText?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit(pin: String) {
        busy = true
        scope.launch {
            try {
                when {
                    purpose == PinPurpose.Unlock -> session.withCore { it.unlockParental(pin) }
                    step == PinStep.Current -> {
                        current = pin
                        step = PinStep.New
                        busy = false
                        return@launch
                    }
                    step == PinStep.New -> {
                        first = pin
                        step = PinStep.Confirm
                        busy = false
                        return@launch
                    }
                    pin != first -> {
                        error = UiText(R.string.pin_mismatch)
                        first = null
                        step = PinStep.New
                        busy = false
                        return@launch
                    }
                    else -> session.withCore { core ->
                        core.setPin(pin, current)
                        core.unlockParental(pin)
                    }
                }
                session.notifyUserDataChanged()
                onSuccess()
            } catch (failure: Throwable) {
                error = failure.toUiText()
                if (purpose == PinPurpose.Change) {
                    current = null
                    first = null
                    step = PinStep.Current
                }
            } finally {
                busy = false
            }
        }
    }

    val title = when {
        purpose == PinPurpose.Unlock -> R.string.pin_title_unlock
        step == PinStep.Current -> R.string.pin_title_current
        step == PinStep.New && purpose == PinPurpose.Create -> R.string.pin_title_create
        step == PinStep.New -> R.string.pin_title_new
        else -> R.string.pin_title_confirm
    }
    val message = if (purpose == PinPurpose.Create && step == PinStep.New) R.string.pin_create_message else null

    Dialog(onDismissRequest = onDismiss) {
        PinPad(
            title = stringResource(title),
            message = message?.let { stringResource(it) },
            entered = entered.length,
            error = error?.resolve(),
            enabled = !busy,
            onDigit = { digit ->
                if (entered.length < PIN_LENGTH) {
                    error = null
                    entered += digit
                    if (entered.length == PIN_LENGTH) {
                        val pin = entered
                        entered = ""
                        submit(pin)
                    }
                }
            },
            onDelete = { entered = entered.dropLast(1) },
            onCancel = onDismiss,
        )
    }
}

/** Pavé de saisie du code (sans logique), pour la boîte de dialogue et les tests. */
@Composable
fun PinPad(
    title: String,
    message: String?,
    entered: Int,
    error: String?,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val dimens = Hs.dimens
    val key = if (dimens.isTv) 48.dp else 56.dp
    Column(
        modifier
            .width(if (dimens.isTv) 300.dp else 340.dp)
            .background(Hs.colors.surface, RoundedCornerShape(dimens.radius))
            .padding(dimens.gapLarge)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !enabled) return@onPreviewKeyEvent false
                val digit = digitOf(event.key)
                when {
                    digit != null -> {
                        onDigit(digit)
                        true
                    }
                    event.key == Key.Backspace -> {
                        onDelete()
                        true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        Text(title, style = Hs.type.itemTitle, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = Hs.type.small, color = Hs.colors.textSecondary, textAlign = TextAlign.Center)
        }
        val dotsLabel = stringResource(R.string.pin_entered, entered, PIN_LENGTH)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.semantics { contentDescription = dotsLabel },
        ) {
            repeat(PIN_LENGTH) { index ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (index < entered) Hs.colors.text else Hs.colors.background)
                        .border(1.5.dp, Hs.colors.textSecondary, CircleShape),
                )
            }
        }
        Text(
            text = error ?: " ",
            style = Hs.type.small,
            color = Hs.colors.text,
            textAlign = TextAlign.Center,
        )
        val rows = listOf("123", "456", "789")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { rowIndex, digits ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    digits.forEachIndexed { columnIndex, digit ->
                        PinKey(
                            label = digit.toString(),
                            size = key,
                            enabled = enabled,
                            modifier = if (rowIndex == 0 && columnIndex == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = { onDigit(digit) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinIconKey(Lucide.X, stringResource(R.string.cancel), key, enabled = true, onClick = onCancel)
                PinKey("0", key, enabled, onClick = { onDigit('0') })
                PinIconKey(Lucide.Delete, stringResource(R.string.pin_delete), key, enabled, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    HsSurface(
        onClick = onClick,
        enabled = enabled,
        color = Hs.colors.surface2,
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Hs.type.title.tabular(), color = LocalContentColor.current)
    }
}

@Composable
private fun PinIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    HsSurface(
        onClick = onClick,
        enabled = enabled,
        color = Hs.colors.surface,
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(Hs.dimens.icon))
    }
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

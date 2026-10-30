package io.github.shweepsi.heystream.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.AppUpdate
import io.github.shweepsi.heystream.data.AppUpdater
import io.github.shweepsi.heystream.data.UpdateState
import io.github.shweepsi.heystream.data.UpdateStep
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import kotlin.math.roundToInt

/** Boîte « Hey Stream x.y.z est disponible », proposée d'elle-même au lancement. */
@Composable
fun UpdatePrompt(updater: AppUpdater) {
    val prompt by updater.prompt.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()
    val update = prompt ?: return
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    Dialog(onDismissRequest = { if (!busy) updater.dismiss() }) {
        UpdatePanel(
            update = update,
            state = state,
            onInstall = { updater.install(update) },
            onLater = updater::dismiss,
        )
    }
}

/** Contenu de la boîte (sans fenêtre), pour la boîte et les tests. */
@Composable
fun UpdatePanel(update: AppUpdate, state: UpdateState, onInstall: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val install = remember { FocusRequester() }
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    LaunchedEffect(Unit) { runCatching { install.requestFocus() } }
    Column(
        modifier
            .widthIn(max = if (dimens.isTv) 460.dp else 520.dp)
            .background(Hs.colors.surface, RoundedCornerShape(dimens.radius))
            .padding(dimens.gapLarge),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        Text(
            stringResource(R.string.update_prompt_title, update.version),
            style = Hs.type.itemTitle,
            modifier = Modifier.semantics { heading() },
        )
        val notes = releaseNotes(update.notes)
        if (notes.isNotEmpty()) {
            Text(
                notes,
                style = Hs.type.small,
                color = Hs.colors.textSecondary,
                modifier = Modifier
                    .heightIn(max = if (dimens.isTv) 160.dp else 200.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        updateStatus(state)?.let { Text(it, style = Hs.type.body) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = dimens.gapSmall),
            horizontalArrangement = Arrangement.spacedBy(dimens.gap, Alignment.End),
        ) {
            HsButton(
                text = stringResource(R.string.update_later),
                onClick = { if (!busy) onLater() },
            )
            // Toujours activé : un bouton désactivé perdrait le focus de la télécommande.
            HsButton(
                text = stringResource(R.string.update_install),
                onClick = { if (!busy) onInstall() },
                icon = Lucide.RotateCw,
                style = ButtonStyle.Primary,
                modifier = Modifier.focusRequester(install),
            )
        }
    }
}

/**
 * Où en est la mise à jour : texte à montrer sous le bouton, ou `null`. La
 * version disponible et « à jour » sont dites par l'écran qui l'affiche.
 */
@Composable
fun updateStatus(state: UpdateState): String? = when (state) {
    is UpdateState.Downloading -> state.progress?.let {
        stringResource(R.string.update_downloading_percent, (it * 100).roundToInt().coerceIn(0, 100))
    } ?: stringResource(R.string.update_downloading)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
    is UpdateState.Failed -> stringResource(
        when (state.step) {
            UpdateStep.Check -> R.string.update_failed_check
            UpdateStep.Download -> R.string.update_failed_download
            UpdateStep.Install -> R.string.update_failed_install
        },
    )
    else -> null
}

/**
 * Notes de la release (entrée du CHANGELOG) sans la mention ajoutée par la
 * publication (après `---`), en texte simple.
 */
fun releaseNotes(markdown: String): String = markdown
    .substringBefore("\n---")
    .lines()
    .joinToString("\n") { line -> line.trimEnd().replace(Regex("^- "), "• ").replace("`", "").replace("**", "") }
    .trim()

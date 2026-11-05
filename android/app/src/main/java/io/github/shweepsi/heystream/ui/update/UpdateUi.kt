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
    val busy = state is UpdateState.Downloading
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
    // « Plus tard » reste possible pendant la confirmation d'Android, pas pendant le téléchargement.
    val busy = state is UpdateState.Downloading
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
            // Le libellé dit où l'on en est (téléchargement, confirmation d'Android) ; le bouton
            // n'agit pas à ces étapes mais reste actif : un bouton désactivé perdrait le focus de la
            // télécommande, et un deuxième appui ouvrirait une nouvelle session d'installation.
            HsButton(
                text = updateButtonLabel(state),
                onClick = { if (!state.buttonInert()) onInstall() },
                icon = Lucide.RotateCw,
                style = ButtonStyle.Primary,
                modifier = Modifier.focusRequester(install),
            )
        }
    }
}

/** Étapes où le bouton n'agit pas : téléchargement, confirmation d'Android, reprise. */
fun UpdateState.buttonInert(): Boolean =
    this is UpdateState.Downloading || this is UpdateState.Installing || this is UpdateState.Resuming

/**
 * Libellé du bouton selon l'étape : « Télécharger et installer » → « Téléchargement… 42 % » →
 * « Confirmez dans la fenêtre d'Android ». [version] : réglages (« … la version x.y.z »), sinon boîte.
 * Après une annulation ou un échec d'installation, l'APK est déjà là : « Installer » suffit.
 */
@Composable
fun updateButtonLabel(state: UpdateState, version: String? = null): String = when {
    state is UpdateState.Downloading -> state.progress?.let {
        stringResource(R.string.update_downloading_percent, (it * 100).roundToInt().coerceIn(0, 100))
    } ?: stringResource(R.string.update_downloading)
    state is UpdateState.Installing -> stringResource(R.string.update_confirm_in_android)
    state is UpdateState.Resuming -> stringResource(R.string.update_resuming)
    state is UpdateState.Aborted || (state is UpdateState.Failed && state.step == UpdateStep.Install) ->
        if (version != null) stringResource(R.string.settings_update_install, version) else stringResource(R.string.update_install_again)
    else ->
        if (version != null) stringResource(R.string.settings_update_download_install, version) else stringResource(R.string.update_install)
}

/**
 * Où en est la mise à jour : texte à montrer sous le bouton, ou `null` (le bouton lui-même dit
 * le téléchargement et la reprise). La version disponible et « à jour » sont dites par l'écran
 * qui l'affiche.
 */
@Composable
fun updateStatus(state: UpdateState): String? = when (state) {
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.NeedsPermission ->
        stringResource(if (state.denied) R.string.update_permission_denied else R.string.update_needs_permission)
    is UpdateState.Aborted -> stringResource(R.string.update_aborted)
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
 * publication (après `---`), en texte simple. Le CHANGELOG coupe ses lignes vers 78 colonnes :
 * les lignes de suite d'un même point (ou d'un même paragraphe) sont rejointes, sinon la liste
 * s'affiche décalée avec un mot orphelin en bout de ligne. Un point commence par « - », un
 * paragraphe par une ligne après une ligne vide.
 */
fun releaseNotes(markdown: String): String {
    val blocks = mutableListOf<String>()
    var open = false
    for (raw in markdown.substringBefore("\n---").lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> {
                if (blocks.isNotEmpty() && blocks.last().isNotEmpty()) blocks.add("")
                open = false
            }
            line.startsWith("- ") -> {
                blocks.add("• " + line.removePrefix("- ").trimStart())
                open = true
            }
            open -> blocks[blocks.lastIndex] = blocks.last() + " " + line
            else -> {
                blocks.add(line)
                open = true
            }
        }
    }
    return blocks.joinToString("\n") { it.replace("`", "").replace("**", "") }.trim()
}

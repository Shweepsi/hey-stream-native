package io.github.shweepsi.heystream.ui.live

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.RecordingState
import io.github.shweepsi.heystream.recording.RecordingNotifications
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.FocusScale
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsIconButton
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.LocalSurfaceFocused
import io.github.shweepsi.heystream.ui.components.MessageState
import io.github.shweepsi.heystream.ui.components.ProgressRail
import io.github.shweepsi.heystream.ui.components.marqueeWhen
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular

/** Taille lisible (« 1,2 Go »), selon la langue de l'appareil. */
@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/** Durée en heures et minutes (« 6 h 05 », « 40 min »). */
@Composable
private fun duration(secs: Long): String {
    val (hours, minutes) = Formats.hoursMinutes(secs)
    return if (hours > 0) stringResource(R.string.duration_hm, hours, minutes) else stringResource(R.string.duration_m, minutes)
}

/** « mar. 3 nov., 21:00 ». */
@Composable
private fun whenText(epochSecs: Long): String {
    val locale = currentLocale()
    return stringResource(R.string.recording_when, Formats.shortDate(epochSecs * 1000, locale), Formats.timeOfSeconds(epochSecs, locale))
}

/**
 * Catégorie Enregistrements, à la place de la liste des chaînes : jauge du
 * disque, puis en cours, programmés et faits. OK regarde (ou ouvre les
 * options d'un programmé) ; appui long ou Menu ouvre les options.
 */
@Composable
internal fun RecordingsPanel(ui: RecordingsUi, actions: LiveActions, compact: Boolean, firstFocus: FocusRequester) {
    val isTv = Hs.isTv
    val vertical = if (isTv) 9.dp else if (compact) 8.dp else 16.dp
    when {
        !ui.loaded -> Unit
        ui.rows.isEmpty() -> MessageState(
            title = stringResource(if (ui.noDisk) R.string.recordings_no_disk else R.string.recordings_empty),
            message = if (ui.noDisk) null else stringResource(if (isTv) R.string.recordings_empty_tv else R.string.recordings_empty_touch),
            icon = Lucide.CircleDot,
        )
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
            contentPadding = PaddingValues(vertical = vertical),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 3.dp else 4.dp),
        ) {
            item(key = "usage", contentType = "usage") { UsageHeader(ui) }
            items(ui.rows, key = { it.id }, contentType = { "recording" }) { row ->
                RecordingRowView(
                    row = row,
                    onClick = { actions.onRecordingClick(row) },
                    onOptions = { actions.onRecordingOptions(row) },
                    modifier = if (row == ui.rows.first()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

/** « 12 Go utilisés · environ 6 h restantes », et la note sur la désinstallation. */
@Composable
private fun UsageHeader(ui: RecordingsUi) {
    val usage = ui.usage
    Column(
        Modifier.padding(horizontal = if (Hs.isTv) 9.dp else 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (usage != null) {
            Text(
                text = stringResource(R.string.recordings_usage, size(usage.usedBytes.toLong()), duration(usage.remainingSecs)),
                style = Hs.type.small.tabular(),
                color = if (usage.low) Hs.colors.text else Hs.colors.textSecondary,
                maxLines = 2,
            )
        }
        Text(stringResource(R.string.recordings_private_note), style = Hs.type.small, color = Hs.colors.textSecondary, maxLines = 2)
    }
}

@Composable
private fun RecordingRowView(row: RecordingRow, onClick: () -> Unit, onOptions: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    HsSurface(
        onClick = onClick,
        onLongClick = onOptions,
        focusScale = FocusScale.NONE,
        contentPadding = PaddingValues(horizontal = if (isTv) 9.dp else 12.dp, vertical = if (isTv) 6.dp else 8.dp),
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.channelRowHeight)
            .onKeyEvent { event ->
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) onOptions()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 12.dp)) {
            val alert = row.problem != null || row.volumeMissing
            Icon(
                imageVector = when {
                    row.state == RecordingState.RECORDING -> Lucide.CircleDot
                    row.state == RecordingState.SCHEDULED -> Lucide.Clock
                    alert -> Lucide.CircleAlert
                    row.watched -> Lucide.Check
                    else -> Lucide.Play
                },
                contentDescription = null,
                tint = if (alert && row.state != RecordingState.RECORDING) HsColors.Alert else LocalContentColor.current,
                modifier = Modifier.size(dimens.icon),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val highlighted = if (isTv) LocalSurfaceFocused.current else false
                val title = if (row.more > 0) stringResource(R.string.recording_more_programmes, row.title, row.more) else row.title
                Text(title, style = Hs.type.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.marqueeWhen(highlighted))
                Text(
                    text = stringResource(R.string.live_joined, statusText(row), row.channel),
                    style = Hs.type.small.tabular(),
                    color = Hs.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.marqueeWhen(highlighted),
                )
                row.progress?.let { ProgressRail(it, Modifier.padding(top = 3.dp).fillMaxWidth()) }
            }
        }
    }
}

/** État, heure et taille d'une ligne (« Programmé · mar. 3 nov., 21:00 »). */
@Composable
private fun statusText(row: RecordingRow): String {
    if (row.volumeMissing && row.state != RecordingState.SCHEDULED) return stringResource(R.string.recordings_volume_missing)
    return when (row.state) {
        RecordingState.SCHEDULED -> stringResource(R.string.recording_state_scheduled, whenText(row.start))
        RecordingState.RECORDING -> stringResource(R.string.recording_state_recording, Formats.timeOfSeconds(row.stop, currentLocale()))
        RecordingState.DONE -> stringResource(R.string.recording_state_done, "${whenText(row.start)} · ${size(row.bytes)}")
        RecordingState.INCOMPLETE -> stringResource(R.string.recording_state_incomplete, "${whenText(row.start)} · ${size(row.bytes)}")
        RecordingState.FAILED -> stringResource(R.string.recording_state_failed)
        RecordingState.MISSED -> stringResource(R.string.recording_state_missed)
    }
}

/** Cadre commun des dialogues de l'enregistrement. */
@Composable
private fun DialogPanel(title: String, subtitle: String?, content: @Composable () -> Unit) {
    val dimens = Hs.dimens
    Column(
        Modifier
            .widthIn(max = if (dimens.isTv) 460.dp else 500.dp)
            .background(Hs.colors.surface, RoundedCornerShape(dimens.radius))
            .verticalScroll(rememberScrollState())
            .padding(dimens.gapLarge),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Hs.type.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = Hs.type.small.tabular(), color = Hs.colors.textSecondary, maxLines = 2)
        }
        content()
    }
}

/** Boutons d'un dialogue, alignés à droite (en colonne s'ils ne tiennent pas). */
@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Hs.dimens.gapSmall)) {
        content()
    }
}

/**
 * « Enregistrer » un programme du guide : marges réglables (dernières
 * valeurs retenues), taille estimée, fusion ou marges raccourcies, place
 * manquante avec ses deux choix. Programme déjà programmé : annulation.
 */
@Composable
internal fun RecordDialog(ui: RecordDialogUi, viewModel: LiveViewModel) {
    val locale = currentLocale()
    val range = stringResource(
        R.string.live_time_range,
        Formats.timeOfSeconds(ui.programme.start, locale),
        Formats.timeOfSeconds(ui.programme.stop, locale),
    )
    val first = remember { FocusRequester() }
    LaunchedEffect(ui.scheduledId, ui.cleanup != null) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = viewModel::onDismissRecordDialog) {
        DialogPanel(ui.programme.title, stringResource(R.string.live_joined, range, ui.channel)) {
            val cleanup = ui.cleanup
            when {
                ui.scheduledId != null -> {
                    Text(stringResource(R.string.record_planned_badge), style = Hs.type.body, color = Hs.colors.textSecondary)
                    DialogButtons {
                        HsButton(
                            stringResource(R.string.record_cancel_action),
                            viewModel::onCancelRecording,
                            icon = Lucide.X,
                            style = ButtonStyle.Primary,
                            modifier = Modifier.focusRequester(first),
                        )
                        HsButton(stringResource(R.string.close), viewModel::onDismissRecordDialog)
                    }
                }
                cleanup != null -> CleanupContent(cleanup, viewModel, first)
                else -> ScheduleContent(ui, viewModel, first)
            }
        }
    }
}

@Composable
private fun ScheduleContent(ui: RecordDialogUi, viewModel: LiveViewModel, first: FocusRequester) {
    MarginRow(stringResource(R.string.record_margin_before), ui.margins.beforeSecs, { viewModel.onAdjustMargin(true, it) }, Modifier.focusRequester(first))
    MarginRow(stringResource(R.string.record_margin_after), ui.margins.afterSecs, { viewModel.onAdjustMargin(false, it) })
    val preview = ui.preview
    if (preview != null) {
        Text(stringResource(R.string.record_estimate, size(preview.estimatedBytes.toLong())), style = Hs.type.body)
        if (preview.mergedInto != null) Text(stringResource(R.string.record_merged), style = Hs.type.small, color = Hs.colors.textSecondary)
        if (preview.marginsShortened) Text(stringResource(R.string.record_shortened), style = Hs.type.small, color = Hs.colors.textSecondary)
    }
    ui.error?.let { error ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.CircleAlert, contentDescription = null, tint = HsColors.Alert, modifier = Modifier.size(Hs.dimens.icon))
            Text(error.resolve(), style = Hs.type.body)
        }
    }
    val missing = preview?.missingBytes?.toLong() ?: 0L
    DialogButtons {
        if (missing > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.HardDrive, contentDescription = null, tint = HsColors.Alert, modifier = Modifier.size(Hs.dimens.icon))
                Text(stringResource(R.string.record_missing_space, size(missing)), style = Hs.type.body)
            }
            HsButton(stringResource(R.string.record_free_space), viewModel::onFreeSpace, icon = Lucide.Trash, style = ButtonStyle.Primary)
            HsButton(stringResource(R.string.record_anyway), viewModel::onConfirmRecord)
        } else {
            HsButton(
                stringResource(R.string.record_action),
                viewModel::onConfirmRecord,
                icon = Lucide.CircleDot,
                style = ButtonStyle.Primary,
                enabled = ui.error == null || ui.preview != null,
            )
        }
        HsButton(stringResource(R.string.cancel), viewModel::onDismissRecordDialog)
    }
}

/** Marge réglable à la minute : moins, valeur, plus. */
@Composable
private fun MarginRow(label: String, secs: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Hs.dimens.gapSmall)) {
        Text(label, style = Hs.type.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        HsIconButton(Lucide.Minus, stringResource(R.string.record_margin_less), { onChange(-MARGIN_STEP_SECS) }, modifier = modifier)
        Text(
            stringResource(R.string.duration_m, secs / 60),
            style = Hs.type.body.tabular(),
            modifier = Modifier.width(if (Hs.isTv) 64.dp else 72.dp),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        HsIconButton(Lucide.Plus, stringResource(R.string.record_margin_more), { onChange(MARGIN_STEP_SECS) })
    }
}

/** « Libérer de la place » : liste de ce qui serait supprimé, puis confirmation. */
@Composable
private fun CleanupContent(rows: List<RecordingRow>, viewModel: LiveViewModel, first: FocusRequester) {
    if (rows.isEmpty()) {
        Text(stringResource(R.string.record_cleanup_none), style = Hs.type.body, color = Hs.colors.textSecondary)
        DialogButtons {
            HsButton(stringResource(R.string.record_anyway), viewModel::onConfirmRecord, style = ButtonStyle.Primary, modifier = Modifier.focusRequester(first))
            HsButton(stringResource(R.string.cancel), viewModel::onCancelCleanup)
        }
        return
    }
    Text(stringResource(R.string.record_cleanup_message, size(rows.sumOf { it.bytes })), style = Hs.type.body, color = Hs.colors.textSecondary)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Text(
                text = stringResource(R.string.live_joined, size(row.bytes), row.title),
                style = Hs.type.body.tabular(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    DialogButtons {
        HsButton(
            stringResource(R.string.record_cleanup_delete),
            viewModel::onConfirmCleanup,
            icon = Lucide.Trash,
            style = ButtonStyle.Primary,
            modifier = Modifier.focusRequester(first),
        )
        HsButton(stringResource(R.string.cancel), viewModel::onCancelCleanup)
    }
}

/** Options d'un enregistrement de la liste. */
@Composable
internal fun RecordingOptionsDialog(row: RecordingRow, viewModel: LiveViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(row.id) { runCatching { first.requestFocus() } }
    val subtitle = row.channel
    Dialog(onDismissRequest = viewModel::onDismissRecordingOptions) {
        DialogPanel(row.title, subtitle) {
            row.problem?.let { problem ->
                Text(stringResource(RecordingNotifications.problemText(problem)), style = Hs.type.body, color = Hs.colors.textSecondary)
            }
            DialogButtons {
                var focusTaken = false
                fun Modifier.firstFocus(): Modifier = if (focusTaken) this else this.focusRequester(first).also { focusTaken = true }
                if (row.playable) {
                    HsButton(
                        stringResource(R.string.recording_watch),
                        { viewModel.onDismissRecordingOptions(); viewModel.onRecordingClick(row) },
                        icon = Lucide.Play,
                        style = ButtonStyle.Primary,
                        modifier = Modifier.firstFocus(),
                    )
                }
                when (row.state) {
                    RecordingState.RECORDING -> HsButton(stringResource(R.string.recording_stop_now), viewModel::onCancelRecording, icon = Lucide.X, modifier = Modifier.firstFocus())
                    RecordingState.SCHEDULED -> HsButton(stringResource(R.string.record_cancel_action), viewModel::onCancelRecording, icon = Lucide.X, modifier = Modifier.firstFocus())
                    else -> HsButton(stringResource(R.string.recording_delete), viewModel::onDeleteRecording, icon = Lucide.Trash, modifier = Modifier.firstFocus())
                }
                HsButton(stringResource(R.string.close), viewModel::onDismissRecordingOptions)
            }
        }
    }
}

/** Première programmation sans alarme exacte : explication et lien vers le réglage. */
@Composable
internal fun ExactAlarmDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(stringResource(R.string.record_exact_alarm_title), null) {
            Text(stringResource(R.string.record_exact_alarm_message), style = Hs.type.body, color = Hs.colors.textSecondary)
            DialogButtons {
                HsButton(stringResource(R.string.record_exact_alarm_open), onOpen, style = ButtonStyle.Primary, modifier = Modifier.focusRequester(first))
                HsButton(stringResource(R.string.record_exact_alarm_later), onDismiss)
            }
        }
    }
}

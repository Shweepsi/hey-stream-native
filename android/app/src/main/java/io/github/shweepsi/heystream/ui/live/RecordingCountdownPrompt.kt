package io.github.shweepsi.heystream.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.recording.RecordingRepository
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Un enregistrement commence dans moins d'une minute pendant une lecture :
 * compte à rebours, puis la lecture s'arrête (sa reprise est gardée). On
 * peut annuler l'enregistrement ; sans réponse, il gagne. Montré par-dessus
 * n'importe quel écran.
 */
@Composable
fun RecordingCountdownPrompt(recordings: RecordingRepository) {
    val countdown by recordings.countdown.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf<Long?>(null) }
    val shown = countdown?.takeIf { it.id != dismissed } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(shown.id) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }
    val scope = rememberCoroutineScope()
    val cancel = remember { FocusRequester() }
    LaunchedEffect(shown.id) { runCatching { cancel.requestFocus() } }
    val left = (shown.startsAt - now).coerceAtLeast(0)
    Dialog(onDismissRequest = { dismissed = shown.id }) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .background(Hs.colors.surface, RoundedCornerShape(Hs.dimens.radius))
                .padding(Hs.dimens.gapLarge),
            verticalArrangement = Arrangement.spacedBy(Hs.dimens.gap),
        ) {
            Text(
                text = stringResource(R.string.recording_countdown, shown.title, Formats.clock(left * 1000)),
                style = Hs.type.body,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Hs.dimens.gapSmall)) {
                HsButton(
                    text = stringResource(R.string.record_cancel_action),
                    onClick = {
                        dismissed = shown.id
                        scope.launch {
                            runCatching { recordings.withStore { it.cancel(shown.id) } }
                            recordings.setCountdown(null)
                            recordings.changed()
                        }
                    },
                    icon = Lucide.X,
                    style = ButtonStyle.Primary,
                    modifier = Modifier.focusRequester(cancel),
                )
                HsButton(text = stringResource(R.string.close), onClick = { dismissed = shown.id })
            }
        }
    }
}

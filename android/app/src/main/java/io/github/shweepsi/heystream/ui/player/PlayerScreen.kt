package io.github.shweepsi.heystream.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.ui.components.MessageState

/** Provisoire : remplacé par l'écran complet. */
@Composable
fun PlayerScreen(kind: PlaybackKind, id: Long, seriesId: Long?, fromStart: Boolean, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    MessageState(title = stringResource(R.string.coming_soon), action = stringResource(R.string.back), onAction = onExit)
}

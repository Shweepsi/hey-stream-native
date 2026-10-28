package io.github.shweepsi.heystream.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.components.MessageState

/** Provisoire : remplacé par l'écran complet. */
@Composable
fun LiveScreen(initialChannelId: Long?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    MessageState(title = stringResource(R.string.coming_soon), action = stringResource(R.string.back), onAction = onBack)
}

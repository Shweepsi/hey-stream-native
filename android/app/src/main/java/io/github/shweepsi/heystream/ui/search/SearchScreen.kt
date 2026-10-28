package io.github.shweepsi.heystream.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.shweepsi.heystream.R

import io.github.shweepsi.heystream.ui.components.MessageState

/** Provisoire : remplacé par l'écran complet. */
@Composable
fun SearchScreen(onOpenChannel: (Long) -> Unit, onOpenMovie: (Long) -> Unit, onOpenSeries: (Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    MessageState(title = stringResource(R.string.coming_soon), action = stringResource(R.string.back), onAction = onBack)
}

package io.github.shweepsi.heystream.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide

/** Fiche film : affiche, infos du fournisseur, reprise, favori, vu. */
@Composable
fun MovieDetailScreen(id: Long, onPlay: (fromStart: Boolean) -> Unit, onBack: () -> Unit) {
    val session = appGraph().session
    val viewModel = viewModel { MovieDetailViewModel(session, id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Seulement à l'écran (pas pendant la lecture) : au retour du lecteur,
    // la reprise et la marque « vu » sont relues une fois.
    val userData by session.userDataVersion.collectAsStateWithLifecycle()
    val catalog by session.catalogVersion.collectAsStateWithLifecycle()
    LaunchedEffect(userData, catalog) { viewModel.onDataVersion(userData, catalog) }
    BackHandler(onBack = onBack)
    MovieDetailContent(
        state = state,
        onPlay = onPlay,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleWatched = viewModel::toggleWatched,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailContent(
    state: MovieDetailState,
    onPlay: (fromStart: Boolean) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    DetailFrame(sectionTitle = stringResource(R.string.detail_movies), onBack = onBack) {
        val movie = state.movie
        when {
            movie != null -> MovieBody(movie, onPlay, onToggleFavorite, onToggleWatched)
            state.error != null -> DetailError(state.error.resolve(), onRetry)
            else -> LoadingState()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieBody(
    movie: MovieDetail,
    onPlay: (fromStart: Boolean) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    val dimens = Hs.dimens
    val first = remember { FocusRequester() }
    // Le premier bouton a le focus à l'ouverture (et au retour du lecteur).
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    PosterLayout(movie.header.image, Lucide.Film) { modifier, padding, compact ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 18.dp else if (compact) 14.dp else 20.dp),
        ) {
            DetailHeaderBlock(movie.header, compact = compact)
            val progress = movie.progress
            if (progress != null) ProgressBlock(progress)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(dimens.gap),
                verticalArrangement = Arrangement.spacedBy(dimens.gap),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = if (dimens.isTv) 6.dp else if (compact) 0.dp else 4.dp),
            ) {
                HsButton(
                    text = stringResource(if (progress != null) R.string.resume else R.string.play),
                    onClick = { onPlay(false) },
                    icon = Lucide.Play,
                    style = ButtonStyle.Primary,
                    modifier = Modifier.focusRequester(first),
                )
                if (progress != null) {
                    HsButton(
                        text = stringResource(R.string.from_start),
                        onClick = { onPlay(true) },
                        icon = Lucide.RotateCcw,
                    )
                }
                FavoriteButton(movie.favorite, onToggleFavorite)
                WatchedButton(movie.watched, onToggleWatched, compact = compact)
            }
        }
    }
}

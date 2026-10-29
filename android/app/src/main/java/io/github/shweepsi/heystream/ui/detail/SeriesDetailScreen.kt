package io.github.shweepsi.heystream.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.FocusScale
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.components.PanelLabel
import io.github.shweepsi.heystream.ui.components.ProgressRail
import io.github.shweepsi.heystream.ui.components.WatchedBadge
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular

/** Fiche série : affiche, infos, reprise, favori, vu ; saisons puis épisodes. */
@Composable
fun SeriesDetailScreen(id: Long, onPlayEpisode: (episodeId: Long, fromStart: Boolean) -> Unit, onBack: () -> Unit) {
    val session = appGraph().session
    val viewModel = viewModel { SeriesDetailViewModel(session, id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Seulement à l'écran (pas pendant la lecture) : au retour du lecteur,
    // l'état des épisodes est relu une fois.
    val userData by session.userDataVersion.collectAsStateWithLifecycle()
    val catalog by session.catalogVersion.collectAsStateWithLifecycle()
    LaunchedEffect(userData, catalog) { viewModel.onDataVersion(userData, catalog) }
    BackHandler(onBack = onBack)
    SeriesDetailContent(
        state = state,
        onPlayEpisode = onPlayEpisode,
        onSelectSeason = viewModel::selectSeason,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleWatched = viewModel::toggleSeriesWatched,
        onToggleEpisodeWatched = viewModel::toggleEpisodeWatched,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

@Composable
fun SeriesDetailContent(
    state: SeriesDetailState,
    onPlayEpisode: (episodeId: Long, fromStart: Boolean) -> Unit,
    onSelectSeason: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleEpisodeWatched: (Long) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    DetailFrame(sectionTitle = stringResource(R.string.detail_series), onBack = onBack) {
        val header = state.header
        when {
            header != null -> SeriesBody(
                state = state,
                header = header,
                onPlayEpisode = onPlayEpisode,
                onSelectSeason = onSelectSeason,
                onToggleFavorite = onToggleFavorite,
                onToggleWatched = onToggleWatched,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
                onRetry = onRetry,
            )
            state.error != null -> DetailError(state.error.resolve(), onRetry)
            else -> LoadingState()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesBody(
    state: SeriesDetailState,
    header: DetailHeader,
    onPlayEpisode: (episodeId: Long, fromStart: Boolean) -> Unit,
    onSelectSeason: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleEpisodeWatched: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val dimens = Hs.dimens
    val first = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    // Épisode lancé depuis cette fiche : il reprend le focus au retour du lecteur.
    var lastPlayed by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        // Les lignes d'une liste paresseuse existent après la première mise en page.
        withFrameNanos { }
        val restored = lastPlayed != null && runCatching { restore.requestFocus() }.getOrDefault(false)
        if (!restored) runCatching { first.requestFocus() }
    }
    val playEpisode: (Long) -> Unit = { episode ->
        lastPlayed = episode
        onPlayEpisode(episode, false)
    }
    val playTarget: (Long) -> Unit = { episode ->
        // Lancé par le premier bouton : c'est lui qui reprend le focus.
        lastPlayed = null
        onPlayEpisode(episode, false)
    }
    PosterLayout(header.image) { modifier, padding, compact ->
        LazyColumn(
            modifier = modifier,
            state = listState,
            contentPadding = padding,
        ) {
            item(key = "header", contentType = "header") {
                DetailHeaderBlock(header, Modifier.padding(bottom = if (dimens.isTv) 18.dp else if (compact) 14.dp else 20.dp), compact)
            }
            item(key = "buttons", contentType = "buttons") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(dimens.gap),
                    verticalArrangement = Arrangement.spacedBy(dimens.gap),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = if (compact) 0.dp else 4.dp, bottom = dimens.gapLarge),
                ) {
                    PrimarySeriesButton(state, first, onPlay = playTarget, onRetry = onRetry)
                    FavoriteButton(state.favorite, onToggleFavorite)
                    if (state.episodes.isNotEmpty() || state.seasons.isNotEmpty()) {
                        WatchedButton(state.watched, onToggleWatched)
                    }
                }
            }
            if (state.seasons.size > 1) {
                item(key = "seasons", contentType = "seasons") {
                    SeasonTabs(state.seasons, state.selectedSeason, onSelectSeason)
                }
            }
            item(key = "episodes-label", contentType = "label") {
                EpisodesLabel(state)
            }
            items(state.episodes, key = { it.id }, contentType = { "episode" }) { episode ->
                EpisodeItem(
                    episode = episode,
                    onClick = { playEpisode(episode.id) },
                    onToggleWatched = { onToggleEpisodeWatched(episode.id) },
                    // Lignes lisibles sur un grand écran : pas plus large que le résumé.
                    modifier = (if (episode.id == lastPlayed) Modifier.focusRequester(restore) else Modifier)
                        .widthIn(max = EPISODE_MAX_WIDTH),
                )
            }
        }
    }
}

/** Largeur maximale d'une ligne d'épisode. */
private val EPISODE_MAX_WIDTH = 720.dp

/**
 * « Reprendre S2 · E3 », sinon « Lecture » (premier épisode non vu) ;
 * « Réessayer » si la liste des épisodes n'a pas pu être chargée.
 */
@Composable
private fun PrimarySeriesButton(
    state: SeriesDetailState,
    focus: FocusRequester,
    onPlay: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val target = state.playTarget
    val retry = target == null && state.episodesError != null
    // Un seul bouton, dont seul le libellé change : il garde le focus quand
    // la liste des épisodes arrive.
    HsButton(
        text = when {
            target?.resume == true -> stringResource(R.string.detail_resume_episode, target.season, target.number)
            retry -> stringResource(R.string.retry)
            else -> stringResource(R.string.play)
        },
        onClick = {
            when {
                target != null -> onPlay(target.episodeId)
                retry -> onRetry()
                // Liste en chargement : rien à lire encore.
                else -> Unit
            }
        },
        icon = if (retry) Lucide.RefreshCw else Lucide.Play,
        style = ButtonStyle.Primary,
        modifier = Modifier.focusRequester(focus),
    )
}

/** Saisons : la saison choisie est mise en avant ; le focus y revient en descendant des boutons. */
@Composable
private fun SeasonTabs(seasons: List<Long>, selected: Long?, onSelect: (Long) -> Unit) {
    val dimens = Hs.dimens
    val selectedFocus = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .focusRestorer(selectedFocus)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
            .padding(bottom = if (dimens.isTv) 10.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapSmall),
    ) {
        seasons.forEach { season ->
            val isSelected = season == selected
            HsSurface(
                onClick = { onSelect(season) },
                selected = isSelected,
                modifier = (if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier)
                    .heightIn(min = dimens.minTouch),
                contentPadding = PaddingValues(horizontal = if (dimens.isTv) 14.dp else 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (season == 0L) stringResource(R.string.detail_specials) else stringResource(R.string.detail_season, season),
                    style = Hs.type.label.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (isSelected) LocalContentColor.current else Hs.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Intitulé « Épisodes », puis l'état de la liste (chargement, erreur, vide) ou l'aide de l'appui long. */
@Composable
private fun EpisodesLabel(state: SeriesDetailState) {
    val dimens = Hs.dimens
    Column(Modifier.padding(bottom = dimens.gapSmall), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelLabel(stringResource(R.string.detail_episodes))
        val message = when {
            state.episodesError != null -> state.episodesError.resolve()
            state.episodesLoading -> stringResource(R.string.detail_episodes_loading)
            state.episodes.isEmpty() -> stringResource(R.string.detail_episodes_empty)
            else -> stringResource(if (dimens.isTv) R.string.detail_episode_hint_tv else R.string.detail_episode_hint_touch)
        }
        Text(
            text = message,
            style = Hs.type.small,
            color = Hs.colors.textSecondary,
            modifier = Modifier.padding(start = if (dimens.isTv) 12.dp else 14.dp),
        )
    }
}

/**
 * Ligne d'épisode : numéro, titre, durée, barre si commencé, pastille si vu.
 * OK le lit (reprise s'il est commencé) ; appui long ou MENU change sa marque « vu ».
 */
@Composable
private fun EpisodeItem(
    episode: EpisodeRow,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val title = episode.title ?: stringResource(R.string.detail_episode_untitled, episode.number)
    HsSurface(
        onClick = onClick,
        onLongClick = onToggleWatched,
        onClickLabel = title,
        focusScale = FocusScale.NONE,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (dimens.isTv) 52.dp else 64.dp)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) onToggleWatched()
                    true
                } else {
                    false
                }
            },
        contentPadding = PaddingValues(horizontal = if (dimens.isTv) 12.dp else 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episode.number.toString(),
                style = Hs.type.body.tabular(),
                color = Hs.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.width(if (dimens.isTv) 26.dp else 32.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = Hs.type.itemTitle,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val duration = episode.durationSecs
                val fraction = episode.progress?.fraction
                if (duration != null || fraction != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (duration != null) {
                            Text(
                                text = durationText(duration),
                                style = Hs.type.small.tabular(),
                                color = Hs.colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                        if (fraction != null) {
                            ProgressRail(fraction, Modifier.width(if (dimens.isTv) 96.dp else 120.dp))
                        }
                    }
                }
            }
            if (episode.watched) WatchedBadge()
        }
    }
}

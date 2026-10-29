package io.github.shweepsi.heystream.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.Clock
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.Logo
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide

/** Élément de l'accueil qui reprend le focus au retour. */
enum class HomeTarget { Live, Movies, Series, Search, Settings }

/** Accueil : trois tuiles, heure et date, abonnement, Rechercher et Réglages. */
@Composable
fun HomeScreen(
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val session = appGraph().session
    val viewModel = viewModel { HomeViewModel(session) }
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    // Au retour, le focus revient sur ce qui a été ouvert.
    var opened by rememberSaveable { mutableStateOf(HomeTarget.Live) }
    HomeContent(
        subscription = subscription,
        onOpenLive = { opened = HomeTarget.Live; onOpenLive() },
        onOpenMovies = { opened = HomeTarget.Movies; onOpenMovies() },
        onOpenSeries = { opened = HomeTarget.Series; onOpenSeries() },
        onOpenSearch = { opened = HomeTarget.Search; onOpenSearch() },
        onOpenSettings = { opened = HomeTarget.Settings; onOpenSettings() },
        focusOn = opened,
    )
}

@Composable
fun HomeContent(
    subscription: Subscription?,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    focusOn: HomeTarget = HomeTarget.Live,
) {
    val dimens = Hs.dimens
    val focus = remember { HomeTarget.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { focus.getValue(focusOn).requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(Hs.colors.background)
            .padding(
                start = dimens.screenHorizontal * 1.6f,
                end = dimens.screenHorizontal * 1.6f,
                top = dimens.screenVertical * 1.25f,
                bottom = dimens.screenVertical * 1.5f,
            ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapLarge * 1.5f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(size = if (dimens.isTv) 30.dp else 32.dp)
            Spacer(Modifier.weight(1f))
            Clock()
        }
        // Tablette : tuiles au plus en 16:9, bloc centré dans la hauteur (sur
        // une tablette 3:2, des tuiles pleine hauteur laissaient un grand vide).
        // TV et téléphone en paysage : la hauteur disponible est déjà plus basse.
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val gap = dimens.gapLarge
            val tileHeight = if (dimens.isTv) maxHeight else minOf(maxHeight, (maxWidth - gap * 2) / 3 * TILE_RATIO)
            HomeTiles(focus, onOpenLive, onOpenMovies, onOpenSeries, Modifier.fillMaxWidth().height(tileHeight))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HsButton(
                stringResource(R.string.home_search), onOpenSearch, icon = Lucide.Search, style = ButtonStyle.Surface,
                modifier = Modifier.focusRequester(focus.getValue(HomeTarget.Search)),
            )
            HsButton(
                stringResource(R.string.home_settings), onOpenSettings, icon = Lucide.Settings, style = ButtonStyle.Surface,
                modifier = Modifier.focusRequester(focus.getValue(HomeTarget.Settings)),
            )
            Spacer(Modifier.weight(1f))
            if (subscription != null) {
                val alert = subscription.needsAttention()
                Text(
                    text = subscription.text(),
                    style = if (alert) Hs.type.body.copy(fontWeight = FontWeight.Bold) else Hs.type.body,
                    color = if (alert) HsColors.Alert else Hs.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Rapport hauteur / largeur maximal d'une tuile de l'accueil (16:9), hors TV. */
private const val TILE_RATIO = 9f / 16f

@Composable
private fun HomeTiles(
    focus: Map<HomeTarget, FocusRequester>,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Hs.dimens.gapLarge)) {
        val tile = Modifier
            .weight(1f)
            .fillMaxHeight()
        HomeTile(
            Lucide.Tv, stringResource(R.string.home_live), stringResource(R.string.home_live_subtitle), onOpenLive,
            tile.focusRequester(focus.getValue(HomeTarget.Live)),
        )
        HomeTile(
            Lucide.Film, stringResource(R.string.home_movies), stringResource(R.string.home_movies_subtitle), onOpenMovies,
            tile.focusRequester(focus.getValue(HomeTarget.Movies)),
        )
        HomeTile(
            Lucide.MonitorPlay, stringResource(R.string.home_series), stringResource(R.string.home_series_subtitle), onOpenSeries,
            tile.focusRequester(focus.getValue(HomeTarget.Series)),
        )
    }
}

@Composable
private fun HomeTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val dimens = Hs.dimens
    HsSurface(
        onClick = onClick,
        color = Hs.colors.surface,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        // Téléphone en paysage : tuile basse, marges et icône réduites pour
        // que le titre et le sous-titre tiennent.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val low = !dimens.isTv && maxHeight < 200.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (dimens.isTv) 27.dp else 28.dp,
                        vertical = if (dimens.isTv) 27.dp else if (low) 18.dp else 28.dp,
                    ),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(if (low) dimens.iconLarge * 0.67f else dimens.iconLarge),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = Hs.type.display, color = LocalContentColor.current, maxLines = 1)
                    Text(subtitle, style = Hs.type.body, color = Hs.colors.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

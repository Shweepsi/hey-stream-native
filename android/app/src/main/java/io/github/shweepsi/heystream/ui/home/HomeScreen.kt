package io.github.shweepsi.heystream.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapLarge),
        ) {
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
                val expired = subscription is Subscription.Expired
                Text(
                    text = subscription.text(),
                    style = if (expired) Hs.type.body.copy(fontWeight = FontWeight.Bold) else Hs.type.body,
                    color = if (expired) Hs.colors.text else Hs.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
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
        contentPadding = PaddingValues(if (dimens.isTv) 27.dp else 28.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(dimens.iconLarge))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = Hs.type.display, color = LocalContentColor.current, maxLines = 1)
                Text(subtitle, style = Hs.type.body, color = Hs.colors.textSecondary, maxLines = 1)
            }
        }
    }
}

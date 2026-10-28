package io.github.shweepsi.heystream.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.SessionState
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.detail.MovieDetailScreen
import io.github.shweepsi.heystream.ui.detail.SeriesDetailScreen
import io.github.shweepsi.heystream.ui.home.HomeScreen
import io.github.shweepsi.heystream.ui.live.LiveScreen
import io.github.shweepsi.heystream.ui.login.LoginScreen
import io.github.shweepsi.heystream.ui.player.PlayerScreen
import io.github.shweepsi.heystream.ui.search.SearchScreen
import io.github.shweepsi.heystream.ui.settings.SettingsScreen
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.vod.VodScreen

/** Changement d'écran : fondu court (docs/DESIGN.md). */
private const val FADE_MS = 180

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val session = appGraph().session
    val state by session.state.collectAsStateWithLifecycle()
    val modifier = Modifier
        .fillMaxSize()
        .background(Hs.colors.background)
    if (state == SessionState.Starting) {
        LoadingState(modifier)
        return
    }
    // Choisi une fois : ensuite, connexion et déconnexion naviguent elles-mêmes.
    val start = remember { if (state == SessionState.Ready) Routes.HOME else Routes.LOGIN }
    NavHost(
        navController = navController,
        startDestination = start,
        modifier = modifier,
        enterTransition = { fadeIn(tween(FADE_MS)) },
        exitTransition = { fadeOut(tween(FADE_MS)) },
        popEnterTransition = { fadeIn(tween(FADE_MS)) },
        popExitTransition = { fadeOut(tween(FADE_MS)) },
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onSignedIn = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenLive = { navController.navigate(Routes.live()) },
                onOpenMovies = { navController.navigate(Routes.vod(Section.MOVIES)) },
                onOpenSeries = { navController.navigate(Routes.vod(Section.SERIES)) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.LIVE,
            arguments = listOf(navArgument("channel") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            LiveScreen(
                initialChannelId = entry.arguments?.getLong("channel")?.takeIf { it >= 0 },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.VOD,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) { entry ->
            val section = Section.valueOf(entry.arguments?.getString("section") ?: Section.MOVIES.name)
            VodScreen(
                section = section,
                onOpenItem = { id ->
                    navController.navigate(if (section == Section.SERIES) Routes.series(id) else Routes.movie(id))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.MOVIE,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            MovieDetailScreen(
                id = id,
                onPlay = { fromStart -> navController.navigate(Routes.player(PlaybackKind.MOVIE, id, fromStart = fromStart)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.SERIES,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            SeriesDetailScreen(
                id = id,
                onPlayEpisode = { episodeId, fromStart ->
                    navController.navigate(Routes.player(PlaybackKind.EPISODE, episodeId, seriesId = id, fromStart = fromStart))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.PLAYER,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.LongType },
                navArgument("seriesId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val args = entry.arguments
            PlayerScreen(
                kind = PlaybackKind.valueOf(args?.getString("kind") ?: PlaybackKind.MOVIE.name),
                id = args?.getLong("id") ?: 0L,
                seriesId = args?.getLong("seriesId")?.takeIf { it >= 0 },
                fromStart = args?.getBoolean("fromStart") ?: false,
                onExit = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenChannel = { id -> navController.navigate(Routes.live(id)) },
                onOpenMovie = { id -> navController.navigate(Routes.movie(id)) },
                onOpenSeries = { id -> navController.navigate(Routes.series(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onSignedOut = {
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

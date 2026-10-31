package io.github.shweepsi.heystream.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.SessionState
import io.github.shweepsi.heystream.data.Telemetry
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

/**
 * L'app s'affiche bord à bord : les écrans qui ne gèrent pas eux-mêmes les
 * barres système restent dans la zone sûre. Ceux qui ont une barre du haut
 * (Direct, Films, Séries, fiches, recherche, réglages) et le lecteur la
 * gèrent eux-mêmes, pour que le fond de la barre aille jusqu'aux bords. Sans
 * effet sur TV.
 */
@Composable
private fun SafeArea(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { content() }
}

/**
 * Un seul écran par appui : un second OK pendant le fondu (l'écran de départ
 * n'est plus au premier plan) n'empile pas la destination une seconde fois.
 */
private fun NavHostController.go(from: NavBackStackEntry, route: String, options: NavOptionsBuilder.() -> Unit = {}) {
    if (from.lifecycle.currentState == Lifecycle.State.RESUMED) navigate(route, options)
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val session = appGraph().session
    val state by session.state.collectAsStateWithLifecycle()
    val modifier = Modifier
        .fillMaxSize()
        .background(Hs.colors.background)
        // Repères visibles par UiAutomator (test de bout en bout).
        .semantics { testTagsAsResourceId = true }
    if (state == SessionState.Starting) {
        LoadingState(modifier)
        return
    }
    // Choisi une fois : ensuite, connexion et déconnexion naviguent elles-mêmes.
    val start = remember { if (state == SessionState.Ready) Routes.HOME else Routes.LOGIN }
    // APK testeur : chaque écran ouvert (modèle de route et ses numéros).
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, arguments ->
            Telemetry.event(
                "screen",
                "route" to destination.route,
                "args" to arguments?.let { args ->
                    // Arguments des routes : des numéros et des sections, jamais d'URL.
                    @Suppress("DEPRECATION")
                    args.keySet().filterNot { it.startsWith("android-support-nav") }.joinToString(",") { "$it=${args.get(it)}" }
                },
            )
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
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
            SafeArea {
                LoginScreen(
                    onSignedIn = { navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                )
            }
        }
        composable(Routes.CHANGE_SERVER) {
            SafeArea {
                LoginScreen(
                    editing = true,
                    // Pas de `go` : appelé une seule fois, à la réussite, et
                    // jamais perdu si l'écran n'est plus au premier plan à cet
                    // instant (panneau de l'Assistant par-dessus l'app).
                    onSignedIn = { navController.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.HOME) { entry ->
            SafeArea {
                HomeScreen(
                    onOpenLive = { navController.go(entry, Routes.live()) },
                    onOpenMovies = { navController.go(entry, Routes.vod(Section.MOVIES)) },
                    onOpenSeries = { navController.go(entry, Routes.vod(Section.SERIES)) },
                    onOpenSearch = { navController.go(entry, Routes.SEARCH) },
                    onOpenSettings = { navController.go(entry, Routes.SETTINGS) },
                )
            }
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
                    navController.go(entry, if (section == Section.SERIES) Routes.series(id) else Routes.movie(id))
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
                onPlay = { fromStart -> navController.go(entry, Routes.player(PlaybackKind.MOVIE, id, fromStart = fromStart)) },
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
                    navController.go(entry, Routes.player(PlaybackKind.EPISODE, episodeId, seriesId = id, fromStart = fromStart))
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
        composable(Routes.SEARCH) { entry ->
            SearchScreen(
                onOpenChannel = { id -> navController.go(entry, Routes.live(id)) },
                onOpenMovie = { id -> navController.go(entry, Routes.movie(id)) },
                onOpenSeries = { id -> navController.go(entry, Routes.series(id)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                onSignedOut = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } },
                onChangeServer = { navController.go(entry, Routes.CHANGE_SERVER) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

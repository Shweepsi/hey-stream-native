package io.github.shweepsi.heystream.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.ui.nav.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Écran de lecture restauré après l'arrêt de l'app en arrière-plan : la
 * navigation le recrée avec les arguments d'origine de la route, mais le
 * titre lu vient du handle de son entrée (comme dans `PlayerScreen`).
 */
@RunWith(AndroidJUnit4::class)
class PlayerRestoreTest {
    @get:Rule
    val compose = createComposeRule()

    /** Le ViewModel de l'écran, réduit à ce qu'il lit dans le handle. */
    internal class Holder(handle: SavedStateHandle) : ViewModel() {
        val args = PlayerArgs(handle)
    }

    @Test
    fun restoredEntryReopensTheTitleOfItsHandleNotTheRoute() {
        val restoration = StateRestorationTester(compose)
        var current: PlayerArgs? = null
        var routeId: Long? = null
        var routeFromStart: Boolean? = null
        restoration.setContent {
            // Arrêt de l'app : les ViewModel sont perdus, l'état enregistré reste.
            val owner = remember {
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                val navController = rememberNavController()
                var opened by rememberSaveable { mutableStateOf(false) }
                NavHost(navController, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {}
                    // Mêmes arguments que la destination du lecteur (AppNavigation).
                    composable(
                        Routes.PLAYER,
                        arguments = listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("id") { type = NavType.LongType },
                            navArgument("seriesId") { type = NavType.LongType; defaultValue = -1L },
                            navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        val route = checkNotNull(entry.arguments)
                        routeId = route.getLong("id")
                        routeFromStart = route.getBoolean("fromStart")
                        val holder = viewModel {
                            val handle = PlayerArgs.seed(
                                createSavedStateHandle(),
                                PlaybackKind.valueOf(checkNotNull(route.getString("kind"))),
                                route.getLong("id"),
                                route.getLong("seriesId").takeIf { it >= 0 },
                                route.getBoolean("fromStart"),
                            )
                            Holder(handle)
                        }
                        current = holder.args
                    }
                }
                LaunchedEffect(Unit) {
                    if (!opened) {
                        opened = true
                        // « Depuis le début » sur l'épisode 21 depuis la fiche série.
                        navController.navigate(Routes.player(PlaybackKind.EPISODE, 21, seriesId = 5, fromStart = true))
                    }
                }
            }
        }
        compose.waitForIdle()
        val first = checkNotNull(current)
        assertEquals(21L, first.id)
        assertTrue(first.fromStart)

        // Épisode 21 regardé, l'épisode 22 enchaîné et regardé jusqu'à 10:00 ;
        // l'app est arrêtée en arrière-plan.
        compose.runOnIdle {
            first.reached(2_000_000)
            first.advanceTo(22)
            first.reached(600_000)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // La route restaurée nomme toujours l'épisode 21, « depuis le début »…
        assertEquals(21L, routeId)
        assertEquals(true, routeFromStart)
        // … mais le nouvel écran lit le handle : l'épisode 22, à 10:00.
        val restored = checkNotNull(current)
        assertNotSame(first, restored)
        assertEquals(PlaybackKind.EPISODE, restored.kind)
        assertEquals(22L, restored.id)
        assertEquals(5L, restored.seriesId)
        assertFalse(restored.fromStart)
        assertEquals(600_000L, restored.startMs)
    }
}

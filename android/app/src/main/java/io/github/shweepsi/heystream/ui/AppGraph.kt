package io.github.shweepsi.heystream.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.shweepsi.heystream.HeyStreamApp
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.recording.RecordingRepository

/** Services partagés par les écrans : le cœur, le lecteur et les enregistrements. */
class AppGraph(val session: CoreSession, val player: PlayerController, val recordings: RecordingRepository)

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph absent") }

/** Le cœur et le lecteur de l'app, pour créer un ViewModel. */
@Composable
fun appGraph(): AppGraph = LocalAppGraph.current

fun HeyStreamApp.graph() = AppGraph(session, player, recordings)

/** Pour les écrans appelés hors de l'activité (aperçus). */
@Composable
fun heyStreamApp(): HeyStreamApp = LocalContext.current.applicationContext as HeyStreamApp

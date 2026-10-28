package io.github.shweepsi.heystream

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import io.github.shweepsi.heystream.ui.LocalAppGraph
import io.github.shweepsi.heystream.ui.graph
import io.github.shweepsi.heystream.ui.nav.AppNavigation
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val app get() = application as HeyStreamApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { app.session.start() }
        val graph = app.graph()
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                HeyStreamTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.player.onForeground()
    }

    /** En arrière-plan, le flux est fermé : l'abonnement n'a souvent qu'une connexion. */
    override fun onStop() {
        app.player.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) app.player.stop()
        super.onDestroy()
    }
}

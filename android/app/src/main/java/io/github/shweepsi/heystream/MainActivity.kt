package io.github.shweepsi.heystream

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import io.github.shweepsi.heystream.ui.LocalAppGraph
import io.github.shweepsi.heystream.ui.graph
import io.github.shweepsi.heystream.ui.nav.AppNavigation
import io.github.shweepsi.heystream.ui.update.UpdatePrompt
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme

class MainActivity : AppCompatActivity() {
    private val app get() = application as HeyStreamApp

    override fun onCreate(savedInstanceState: Bundle?) {
        // Toujours sombre : icônes claires des barres système, même si le
        // téléphone est en thème clair.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        app.session.ensureStarted()
        val graph = app.graph()
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                HeyStreamTheme {
                    AppNavigation()
                    UpdatePrompt(app.updater)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.player.onForeground()
        app.updater.onForeground()
    }

    /**
     * En arrière-plan, le flux est fermé (l'abonnement n'a souvent qu'une
     * connexion) et les catégories adultes sont reverrouillées. Pas lors d'une
     * simple recréation (changement de langue).
     *
     * Le reverrouillage se termine hors du fil principal : le flux est retenu
     * jusqu'à ce que son écran, une fois le verrou posé, ait vérifié qu'il
     * reste visible. Un retour rapide ne rouvre donc rien d'adulte.
     */
    override fun onStop() {
        app.player.onBackground()
        if (!isChangingConfigurations) {
            app.session.lockParental()
            app.player.hold()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) app.player.stop()
        super.onDestroy()
    }
}

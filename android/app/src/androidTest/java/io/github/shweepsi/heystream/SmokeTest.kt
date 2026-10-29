package io.github.shweepsi.heystream

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.shweepsi.heystream.ui.login.LoginTags
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * De bout en bout sur un vrai Android (émulateur de la CI) : la bibliothèque
 * du cœur se charge, la connexion à un faux panneau local réussit, le
 * catalogue se télécharge et le Direct l'affiche. L'app est pilotée comme par
 * un utilisateur (UiAutomator) : elle tourne exactement comme en production.
 * Aucun vrai fournisseur n'est contacté.
 */
// Ordre alphabétique : le parcours complet passe en premier, dans un
// processus où rien n'a encore chargé le cœur (comme pour un utilisateur).
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val server = MockWebServer()

    @Before
    fun startPanel() {
        server.dispatcher = FakePanel
        server.start()
    }

    @After
    fun stopPanel() {
        server.shutdown()
    }

    @Test
    fun appSignsInDownloadsTheCatalogAndOpensLive() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        // Sur un téléphone en paysage, le formulaire défile : champ par champ.
        val address = server.url("/").toString().trimEnd('/')
        fill(LoginTags.SERVER, address)
        fill(LoginTags.USERNAME, "utilisateur")
        fill(LoginTags.PASSWORD, "motdepasse", secret = true)
        hideKeyboard()
        step("bouton de connexion") {
            device.findObject(By.text(context.getString(R.string.login_submit))) ?: scrollDown()
        }.click()

        step("accueil après connexion") { device.findObject(By.textContains(context.getString(R.string.home_live))) }.click()

        step("chaîne du catalogue dans le Direct") { device.findObject(By.textContains(CHANNEL)) }
    }

    /** La bibliothèque du cœur se charge et répond par JNA, comme dans l'app. */
    @Test
    fun coreLibraryLoads() {
        System.loadLibrary("heystream")
        assertTrue(io.github.shweepsi.heystream.core.coreVersion().isNotBlank())
    }

    /**
     * Remplit un champ désigné par son repère, en faisant défiler le formulaire
     * s'il le faut, puis attend de relire la valeur : l'arbre d'accessibilité
     * de Compose se met à jour avec un léger retard. Un mot de passe, masqué,
     * n'est pas relu.
     */
    private fun fill(tag: String, value: String, secret: Boolean = false) {
        val field = step("champ $tag") { device.findObject(By.res(tag)) ?: scrollDown() }
        field.text = value
        if (secret) return
        step("valeur du champ $tag") { device.findObject(By.res(tag))?.takeIf { it.text == value } }
    }

    private fun scrollDown(): Nothing? {
        hideKeyboard()
        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.5f)
        return null
    }

    /** Le clavier virtuel peut cacher le bouton : Retour le ferme, sans quitter l'écran. */
    private fun hideKeyboard() {
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            device.waitForIdle()
        }
    }

    /** Attend un élément ; un échec nomme l'étape et montre les textes à l'écran. */
    private fun <T : Any> step(name: String, find: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            runCatching(find).getOrNull()?.let { return it }
            SystemClock.sleep(250)
        }
        val texts = runCatching {
            device.findObjects(By.pkg(context.packageName)).mapNotNull(UiObject2::getText).distinct()
        }.getOrDefault(emptyList())
        throw AssertionError(
            "Étape non atteinte en ${TIMEOUT / 1000} s : $name\n" +
                "Requêtes reçues par le faux panneau : ${server.requestCount}\n" +
                "Textes à l'écran : ${texts.joinToString(" | ")}",
        )
    }

    private object FakePanel : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl
            if (url == null || !url.encodedPath.endsWith("player_api.php")) return MockResponse().setResponseCode(404)
            val body = when (url.queryParameter("action")) {
                null -> """{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","max_connections":"1","active_cons":"0","allowed_output_formats":["ts","m3u8"]}}"""
                "get_live_categories" -> """[{"category_id":"1","category_name":"Infos"}]"""
                "get_live_streams" -> """[{"num":1,"stream_id":5,"name":"$CHANNEL","category_id":"1"}]"""
                "get_vod_categories" -> """[{"category_id":"2","category_name":"Films test"}]"""
                "get_vod_streams" -> """[{"stream_id":7,"name":"Film test","category_id":"2","container_extension":"mp4"}]"""
                "get_series_categories" -> """[{"category_id":"3","category_name":"Séries test"}]"""
                "get_series" -> """[{"series_id":9,"name":"Série test","category_id":"3"}]"""
                "get_short_epg" -> """{"epg_listings":[]}"""
                else -> "[]"
            }
            return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        }
    }

    private companion object {
        const val CHANNEL = "Chaîne de test"
        const val TIMEOUT = 30_000L
    }
}

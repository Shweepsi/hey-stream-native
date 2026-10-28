package io.github.shweepsi.heystream

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * De bout en bout sur un vrai Android (émulateur de la CI) : la bibliothèque
 * du cœur se charge, la connexion à un faux panneau local réussit, le
 * catalogue se télécharge et le Direct l'affiche. Aucun vrai fournisseur
 * n'est contacté.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
    fun signInDownloadsTheCatalogAndOpensLive() {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(server.url("/").toString().trimEnd('/'))
        fields[1].performTextInput("utilisateur")
        fields[2].performTextInput("motdepasse")
        compose.onNodeWithText(context.getString(R.string.login_submit)).performClick()

        val live = context.getString(R.string.home_live)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(androidx.compose.ui.test.hasText(live)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(live).performClick()

        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(CHANNEL)).fetchSemanticsNodes().isNotEmpty()
        }
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

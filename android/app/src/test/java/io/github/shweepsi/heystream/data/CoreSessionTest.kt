package io.github.shweepsi.heystream.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SectionRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Connexion et rafraîchissement, avec un faux cœur (aucun fournisseur). */
@RunWith(AndroidJUnit4::class)
class CoreSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = SupervisorJob()
    private val appScope = CoroutineScope(app + Dispatchers.Default)

    @Before
    fun keystore() = FakeAndroidKeyStore.install()

    @After
    fun stop() = app.cancel()

    /** Session dont chaque connexion ouvre le cœur suivant de [cores]. */
    private fun session(vararg cores: FakeCore): CoreSession {
        val next = cores.iterator()
        return CoreSession(context, CredentialStore(context), appScope, open = { next.next() })
    }

    /** Attend les tâches lancées dans la portée de l'app (rafraîchissements). */
    private suspend fun awaitBackground() = app.children.toList().forEach { it.join() }

    @Test
    fun signInIsPublishedEvenIfTheScreenThatAskedIsGone() = runBlocking {
        val checking = Gate()
        val core = FakeCore(checking = checking)
        val session = session(core)
        val screen = launch(Dispatchers.Default) { session.signIn("http://panel.invalid", "u", "p") }
        checking.awaitReached()
        // Retour pendant « Vérification… » : l'écran et sa portée disparaissent.
        screen.cancel()
        checking.open()
        screen.join()
        awaitBackground()

        assertEquals("http://panel.invalid", CredentialStore(context).load()?.server)
        assertEquals(SessionState.Ready, session.state.value)
        assertEquals(FakeCore.ACCOUNT, session.account.value)
        assertEquals(1L, session.catalogVersion.value)
        assertEquals(1L, session.userDataVersion.value)
        assertTrue("catalogue du nouvel abonnement rechargé", core.refreshed)
    }

    @Test
    fun aReplacedCoreDoesNotOverwriteTheNewRefresh() = runBlocking {
        // Mêmes identifiants confirmés pendant un « Mettre à jour » : l'ancien
        // cœur, lié au même abonnement, finit d'enregistrer après le nouveau.
        val downloading = Gate()
        val old = FakeCore(downloading = downloading, sections = listOf(live(items = 1u)))
        val new = FakeCore(sections = listOf(live(items = 0u, refreshed = false)))
        val session = session(old, new)
        session.signIn("http://panel.invalid", "a", "p")
        downloading.awaitReached()
        session.signIn("http://panel.invalid", "a", "p")
        val done = withTimeout(10_000) { session.refresh.first { it is RefreshState.Done } }
        assertEquals(RefreshState.Done(listOf(live(items = 0u, refreshed = false))), done)
        val version = session.catalogVersion.value

        downloading.open()
        awaitBackground()
        assertTrue(old.refreshed)
        assertEquals(done, session.refresh.value)
        // Le cache partagé a changé : les listes se rechargent.
        assertTrue(session.catalogVersion.value > version)
    }

    @Test
    fun aReplacedCoreThatIsRefusedDoesNotOverwriteTheNewRefresh() = runBlocking {
        // Autre identifiant : le cœur remplacé n'écrit plus rien (SignedOut).
        val downloading = Gate()
        val old = FakeCore(downloading = downloading, failure = HeyStreamException.SignedOut())
        val new = FakeCore(sections = listOf(live(items = 2u)))
        val session = session(old, new)
        session.signIn("http://panel.invalid", "a", "p")
        downloading.awaitReached()
        session.signIn("http://autre.invalid", "b", "p")
        val done = withTimeout(10_000) { session.refresh.first { it is RefreshState.Done } }
        val version = session.catalogVersion.value

        downloading.open()
        awaitBackground()
        assertTrue(old.refreshed)
        assertEquals(done, session.refresh.value)
        assertEquals(version, session.catalogVersion.value)
    }

    private fun live(items: ULong, refreshed: Boolean = true) =
        SectionRefresh(section = Section.LIVE, refreshed = refreshed, keptCache = false, failed = false, items = items)
}

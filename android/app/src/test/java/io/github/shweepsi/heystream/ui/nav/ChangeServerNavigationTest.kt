package io.github.shweepsi.heystream.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.data.CredentialStore
import io.github.shweepsi.heystream.data.FakeAndroidKeyStore
import io.github.shweepsi.heystream.data.FakeCore
import io.github.shweepsi.heystream.data.Gate
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.ui.AppGraph
import io.github.shweepsi.heystream.ui.LocalAppGraph
import io.github.shweepsi.heystream.ui.login.LoginTags
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Réglages › « Modifier l'adresse du serveur », avec un faux cœur (aucun fournisseur). */
@RunWith(AndroidJUnit4::class)
class ChangeServerNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = SupervisorJob()
    private val checking = Gate()
    private lateinit var nav: NavHostController

    @Before
    fun openChangeServer() {
        FakeAndroidKeyStore.install()
        val context = compose.activity.applicationContext
        val session = CoreSession(context, CredentialStore(context), CoroutineScope(app + Dispatchers.Default), open = {
            FakeCore(checking = checking)
        })
        runBlocking { session.start() }
        val graph = AppGraph(session, PlayerController(context))
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalAppGraph provides graph) {
                HeyStreamTheme { AppNavigation(nav) }
            }
        }
        compose.runOnUiThread { nav.navigate(Routes.CHANGE_SERVER) }
        compose.waitForIdle()
        compose.onNodeWithTag(LoginTags.SERVER).performTextInput("http://panel.invalid")
        compose.onNodeWithText(compose.activity.getString(R.string.login_submit)).performClick()
        checking.awaitReached()
    }

    @After
    fun stop() {
        checking.open()
        app.cancel()
    }

    private fun route() = nav.currentDestination?.route

    private fun awaitHome() {
        // La réponse du faux cœur revient sur le fil principal : on le fait
        // tourner jusqu'à elle.
        val deadline = System.currentTimeMillis() + 5_000
        while (route() != Routes.HOME && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            compose.waitForIdle()
        }
        assertEquals(Routes.HOME, route())
        // Plus de retour possible vers le formulaire ni l'ancien abonnement.
        assertNull(nav.previousBackStackEntry)
    }

    @Test
    fun backIsIgnoredWhileTheAddressIsChecked() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(Routes.CHANGE_SERVER, route())

        checking.open()
        awaitHome()
    }

    @Test
    fun successLeavesTheFormEvenIfTheAppIsPausedAtThatMoment() {
        // Panneau de l'Assistant par-dessus l'app : en pause, toujours visible.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        checking.open()
        awaitHome()
    }
}

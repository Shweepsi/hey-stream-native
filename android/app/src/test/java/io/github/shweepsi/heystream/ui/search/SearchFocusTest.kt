package io.github.shweepsi.heystream.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Télécommande : Robolectric reste en mode tactile, où rien ne prend le focus. */
private val KeyboardInput = object : InputModeManager {
    override val inputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

/** Parcours de la recherche à la télécommande (TV), sur des résultats factices. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SearchFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun hit(section: Section, id: Long, name: String) =
        SearchHit(section, id, name, image = null, number = null, progress = null, watched = false)

    private val found = SearchResults.Found(
        term = "monde",
        channels = listOf(hit(Section.LIVE, 1, "Info Monde"), hit(Section.LIVE, 2, "Monde 24")),
        movies = (11L..18L).map { hit(Section.MOVIES, it, "Film $it") },
        series = listOf(hit(Section.SERIES, 21, "Série 21")),
    )

    private fun show(results: SearchResults = found, focusKey: String? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides KeyboardInput) {
                HeyStreamTheme(isTv = true, systemDark = true) {
                    SearchContent(query = "monde", results = results, onQueryChange = {}, onOpen = {}, onBack = {}, focusKey = focusKey)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun clickable(text: String): SemanticsNodeInteraction = compose.onNode(
        (hasText(text) and hasClickAction()) or
            SemanticsMatcher("clic « $text »") { it.config.getOrNull(SemanticsActions.OnClick)?.label == text },
    )

    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    @Test
    fun downEntersTheResultsAndUpComesBackToTheField() {
        show()
        field().assertIsFocused()
        press(Key.DirectionDown)
        clickable("Info Monde").assertIsFocused()
        press(Key.DirectionDown)
        clickable("Film 11").assertIsFocused()
        press(Key.DirectionRight)
        clickable("Film 12").assertIsFocused()
        press(Key.DirectionDown)
        clickable("Série 21").assertIsFocused()
        press(Key.DirectionUp)
        clickable("Film 12").assertIsFocused()
        press(Key.DirectionUp)
        clickable("Info Monde").assertIsFocused()
        press(Key.DirectionUp)
        field().assertIsFocused()
        // Bas à nouveau : le résultat quitté.
        press(Key.DirectionDown)
        clickable("Info Monde").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        field().assertIsFocused()
        press(Key.DirectionDown)
        clickable("Monde 24").assertIsFocused()
    }

    @Test
    fun downGoesToTheFirstMovieWithoutChannels() {
        show(results = found.copy(channels = emptyList()))
        press(Key.DirectionDown)
        clickable("Film 11").assertIsFocused()
    }

    @Test
    fun focusReturnsToTheOpenedResult() {
        show(focusKey = "MOVIES:14")
        clickable("Film 14").assertIsFocused()
    }

    @Test
    fun downWithoutResultsKeepsTheField() {
        show(results = SearchResults.Found("zzz", emptyList(), emptyList(), emptyList()))
        field().assertIsFocused()
        press(Key.DirectionDown)
        field().assertIsFocused()
    }
}

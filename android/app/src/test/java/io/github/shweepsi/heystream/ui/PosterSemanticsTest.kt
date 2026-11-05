package io.github.shweepsi.heystream.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.ui.components.PosterCard
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lecteur d'écran : l'affiche se lit une fois, par son titre, son état « vu » et son avancement. */
@RunWith(AndroidJUnit4::class)
class PosterSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun posterIsReadByItsTitleOnly() {
        compose.setContent {
            HeyStreamTheme { PosterCard(title = "Heat (1995)", imageUrl = null, onClick = {}) }
        }
        compose.onNode(hasClickAction()).assertContentDescriptionEquals("Heat (1995)")
        // Le titre écrit dessous sort de l'arbre : il serait lu deux fois.
        compose.onNodeWithText("Heat (1995)").assertDoesNotExist()
    }

    @Test
    fun watchedPosterSaysSo() {
        compose.setContent {
            HeyStreamTheme { PosterCard(title = "Heat (1995)", imageUrl = null, onClick = {}, watched = true) }
        }
        compose.onNode(hasClickAction()).assertContentDescriptionEquals("Heat (1995)", "Watched")
    }

    @Test
    fun startedPosterGivesItsProgress() {
        compose.setContent {
            HeyStreamTheme { PosterCard(title = "Heat (1995)", imageUrl = null, onClick = {}, progress = 0.42f) }
        }
        compose.onNode(hasClickAction()).assertContentDescriptionEquals("Heat (1995), 42% watched")
    }
}

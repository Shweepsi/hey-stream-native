package io.github.shweepsi.heystream.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Le déverrouillage prend fin à chaque arrêt de l'app (`MainActivity.onStop`) :
 * arrière-plan, mais aussi écran éteint ou mis en veille, sans avoir quitté
 * l'app. Les réglages le disent, dans les deux langues.
 */
@RunWith(AndroidJUnit4::class)
class ParentalCopyTest {
    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    @Config(qualifiers = "fr")
    fun frenchCopyMatchesTheRelock() {
        assertTrue(text(R.string.settings_parental_unlocked).contains("tant que l’app reste à l’écran"))
        val note = text(R.string.settings_parental_note)
        assertTrue(note.contains("arrière-plan") && note.contains("l’écran s’éteint"))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishCopyMatchesTheRelock() {
        assertTrue(text(R.string.settings_parental_unlocked).contains("while the app stays on screen"))
        val note = text(R.string.settings_parental_note)
        assertTrue(note.contains("background") && note.contains("screen turns off"))
    }
}

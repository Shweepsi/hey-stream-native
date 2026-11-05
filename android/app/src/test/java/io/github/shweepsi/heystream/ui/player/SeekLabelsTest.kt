package io.github.shweepsi.heystream.ui.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Les libellés des boutons d'avance et de recul disent le pas réel (30 s en avant, 10 s en arrière). */
@RunWith(AndroidJUnit4::class)
class SeekLabelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun englishLabelsFollowTheSteps() {
        assertEquals("Forward 30 s", context.getString(R.string.player_forward, FORWARD_STEP_MS / 1000))
        assertEquals("Back 10 s", context.getString(R.string.player_rewind, BACK_STEP_MS / 1000))
    }

    @Test
    @Config(qualifiers = "fr")
    fun frenchLabelsFollowTheSteps() {
        assertEquals("Avancer de 30 s", context.getString(R.string.player_forward, FORWARD_STEP_MS / 1000))
        assertEquals("Reculer de 10 s", context.getString(R.string.player_rewind, BACK_STEP_MS / 1000))
    }
}

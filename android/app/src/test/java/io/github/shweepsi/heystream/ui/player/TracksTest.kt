package io.github.shweepsi.heystream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracksTest {
    private fun audio(id: String, language: String?, channels: Int, label: String? = null) =
        Format.Builder().setId(id).setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language)
            .setChannelCount(channels).setLabel(label).build()

    private fun text(id: String, language: String?) =
        Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(language).build()

    private fun group(format: Format, supported: Boolean = true, selected: Boolean = false) = Tracks.Group(
        TrackGroup(format),
        false,
        intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE),
        booleanArrayOf(selected),
    )

    private val video = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
        false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(true),
    )

    private val tracks = Tracks(
        listOf(
            video,
            group(audio("a1", "fre", 6), selected = true),
            group(audio("a2", "en", 2, label = "Commentary")),
            group(audio("a3", "de", 2), supported = false),
            group(text("t1", "fr")),
            group(text("t2", "und")),
        ),
    )

    @Test
    fun listsOnlyPlayableAudioAndSubtitleTracks() {
        val lists = trackLists(tracks)
        assertEquals(listOf(TrackKey(1, 0), TrackKey(2, 0)), lists.audio.map { it.key })
        assertEquals(listOf(1, 2), lists.audio.map { it.number })
        assertTrue(lists.audio[0].selected)
        assertEquals("fr", lists.audio[0].language)
        assertEquals(6, lists.audio[0].channels)
        assertEquals("Commentary", lists.audio[1].label)
        assertEquals(listOf(TrackKey(4, 0), TrackKey(5, 0)), lists.text.map { it.key })
        // Langue indéterminée : rien à montrer.
        assertNull(lists.text[1].language)
        assertTrue(lists.textOff)
    }

    @Test
    fun selectingATrackOverridesItsTypeAndEnablesIt() {
        val start = TrackSelectionParameters.DEFAULT.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        val chosen = selectTrack(start, tracks, TrackKey(4, 0))
        assertFalse(C.TRACK_TYPE_TEXT in chosen.disabledTrackTypes)
        val override = chosen.overrides.values.single()
        assertEquals(C.TRACK_TYPE_TEXT, override.type)
        assertEquals(listOf(0), override.trackIndices)

        val off = disableSubtitles(chosen)
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertTrue(off.overrides.isEmpty())

        // Piste disparue entre-temps : rien ne change.
        assertEquals(start, selectTrack(start, tracks, TrackKey(42, 0)))
    }

    @Test
    fun trackNamesFollowTheAppLanguage() {
        assertEquals("Français", languageName("fr", Locale.FRANCE))
        assertEquals("Anglais", languageName("en", Locale.FRANCE))
        assertEquals("English", languageName("en", Locale.US))
        assertNull(languageName(null, Locale.FRANCE))
        assertNull(languageName("qqq", Locale.FRANCE))

        val lists = trackLists(tracks)
        assertEquals("Français · 5.1", trackDescription(lists.audio[0], Locale.FRANCE))
        assertEquals("Anglais · Commentary", trackDescription(lists.audio[1], Locale.FRANCE))
        assertNull(trackDescription(lists.text[1], Locale.FRANCE))
        assertNull(channelLayout(2))
        assertEquals("7.1", channelLayout(8))
    }
}

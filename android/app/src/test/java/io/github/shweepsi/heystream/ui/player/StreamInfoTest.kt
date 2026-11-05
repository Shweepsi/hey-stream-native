package io.github.shweepsi.heystream.ui.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.github.shweepsi.heystream.player.Hdr
import io.github.shweepsi.heystream.player.audioInfoOf
import io.github.shweepsi.heystream.player.hdrOf
import io.github.shweepsi.heystream.player.videoInfoOf
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Écran « infos du flux » : ce que le lecteur annonce, et sa mise en forme. */
@UnstableApi
class StreamInfoTest {
    private fun hevc(transfer: Int?) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setWidth(3840)
        .setHeight(2160)
        .setFrameRate(23.976f)
        .setAverageBitrate(18_400_000)
        .apply { if (transfer != null) setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()) }
        .build()

    @Test
    fun hdrComesFromTheColorMetadata() {
        assertEquals(Hdr.Hdr10, hdrOf(hevc(C.COLOR_TRANSFER_ST2084)))
        assertEquals(Hdr.Hlg, hdrOf(hevc(C.COLOR_TRANSFER_HLG)))
        assertEquals(Hdr.None, hdrOf(hevc(C.COLOR_TRANSFER_SDR)))
        assertEquals(Hdr.None, hdrOf(hevc(null)))
        assertEquals(Hdr.DolbyVision, hdrOf(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()))
    }

    @Test
    fun videoInfoKeepsTheOpenDecoder() {
        val info = videoInfoOf(hevc(C.COLOR_TRANSFER_ST2084), decoder = "c2.amlogic.hevc.decoder")
        assertEquals(MimeTypes.VIDEO_H265, info.mime)
        assertEquals(3840 to 2160, info.width to info.height)
        assertEquals(23.976f, info.frameRate, 0.001f)
        assertEquals(Hdr.Hdr10, info.hdr)
        assertEquals("c2.amlogic.hevc.decoder", info.decoder)
    }

    @Test
    fun unknownValuesStayZero() {
        val info = videoInfoOf(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        assertEquals(0, info.width)
        assertEquals(0f, info.frameRate, 0f)
        val audio = audioInfoOf(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())
        assertEquals(0, audio.channels)
        assertEquals(0, audio.sampleRate)
    }

    @Test
    fun labelsAreShortAndFollowTheLocale() {
        assertEquals("H.265", codecLabel("video/hevc", null))
        assertEquals("E-AC-3", codecLabel("audio/eac3", null))
        assertEquals("DTS", codecLabel("audio/vnd.dts.hd", null))
        assertEquals("x-unknown", codecLabel("video/x-unknown", null))
        assertEquals("avc1.640028", codecLabel(null, "avc1.640028"))
        assertEquals("3840×2160", resolutionLabel(3840, 2160))
        assertNull(resolutionLabel(0, 1080))
        assertEquals("23,976", frameRateLabel(23.976f, Locale.FRANCE))
        assertEquals("23.976", frameRateLabel(23.976f, Locale.US))
        assertEquals("25", frameRateLabel(25f, Locale.FRANCE))
        assertEquals("5.1", channelsLabel(6))
        assertEquals("2.0", channelsLabel(2))
        assertEquals("3 ch", channelsLabel(3))
        assertNull(channelsLabel(0))
        assertEquals("48 kHz", sampleRateLabel(48_000, Locale.FRANCE))
        assertEquals("44,1 kHz", sampleRateLabel(44_100, Locale.FRANCE))
        assertEquals("18,4 Mb/s", bitrateLabel(18_400_000, Locale.FRANCE))
        assertEquals("640 kb/s", bitrateLabel(640_000, Locale.FRANCE))
        assertNull(bitrateLabel(0, Locale.FRANCE))
        assertEquals("HDR10", hdrLabel(Hdr.Hdr10))
        assertEquals("SDR", hdrLabel(Hdr.None))
    }
}

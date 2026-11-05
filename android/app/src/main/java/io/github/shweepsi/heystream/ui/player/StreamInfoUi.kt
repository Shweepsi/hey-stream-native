package io.github.shweepsi.heystream.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.player.Hdr
import io.github.shweepsi.heystream.player.StreamInfo
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.tabular
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Écran « infos du flux » (appui long sur OK) : ce que le lecteur lit vraiment, pour vérifier d'un
 * coup d'œil le codec, la résolution, le HDR, le débit, les images perdues et le décodeur.
 */
@Composable
fun StreamInfoPanel(info: StreamInfo, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val unknown = stringResource(R.string.stream_info_unknown)
    val video = info.video
    val audio = info.audio
    val fps = video?.frameRate?.takeIf { it > 0f }?.let { stringResource(R.string.stream_info_fps, frameRateLabel(it, locale)) }
    val videoLine = listOfNotNull(
        video?.let { codecLabel(it.mime, it.codecs) },
        video?.let { resolutionLabel(it.width, it.height) },
        fps,
        video?.takeIf { it.width > 0 || it.mime != null }?.let { hdrLabel(it.hdr) },
    ).joinToString(" · ")
    val audioLine = listOfNotNull(
        audio?.let { codecLabel(it.mime, null) },
        audio?.let { channelsLabel(it.channels) },
        audio?.let { sampleRateLabel(it.sampleRate, locale) },
    ).joinToString(" · ")
    Column(
        modifier
            .widthIn(max = if (Hs.isTv) 420.dp else 460.dp)
            .background(HsColors.MediaScrim, RoundedCornerShape(Hs.dimens.radius))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            stringResource(R.string.stream_info_title),
            style = Hs.type.itemTitle,
            color = HsColors.OnMedia,
            modifier = Modifier.semantics { heading() },
        )
        InfoRow(R.string.stream_info_video, videoLine.ifEmpty { unknown })
        InfoRow(R.string.stream_info_video_bitrate, bitrateLabel(video?.bitrate?.toLong() ?: 0, locale) ?: unknown)
        InfoRow(R.string.stream_info_video_decoder, video?.decoder ?: unknown)
        InfoRow(R.string.stream_info_audio, audioLine.ifEmpty { unknown })
        InfoRow(R.string.stream_info_audio_decoder, audio?.decoder ?: unknown)
        InfoRow(R.string.stream_info_network, bitrateLabel(info.bandwidthBps, locale) ?: unknown)
        InfoRow(R.string.stream_info_dropped, info.droppedFrames.toString())
    }
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(label),
            style = Hs.type.small,
            color = HsColors.OnMedia.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(min = if (Hs.isTv) 104.dp else 116.dp),
        )
        Text(value, style = Hs.type.small.tabular(), color = HsColors.OnMedia)
    }
}

/** Nom courant d'un codec d'après son type MIME (« H.265 »), sinon la fin du type (« vnd.dts »). */
internal fun codecLabel(mime: String?, codecs: String?): String? {
    val name = when (mime) {
        null -> return codecs
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 JOC"
        "audio/true-hd" -> "TrueHD"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-L2" -> "MP2"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> if (mime.startsWith("audio/vnd.dts")) "DTS" else mime.substringAfter('/')
    }
    return name
}

/** « 3840×2160 », ou `null` si la taille est inconnue. */
internal fun resolutionLabel(width: Int, height: Int): String? = if (width > 0 && height > 0) "$width×$height" else null

/** « 23,976 », « 25 » : trois décimales au plus, sans zéros inutiles, selon la langue. */
internal fun frameRateLabel(fps: Float, locale: Locale): String =
    DecimalFormat("0.###", DecimalFormatSymbols(locale)).format(fps.toDouble())

/** « HDR10 », « HLG », « Dolby Vision » ; « SDR » sans HDR dans le flux. */
internal fun hdrLabel(hdr: Hdr): String = when (hdr) {
    Hdr.Hdr10 -> "HDR10"
    Hdr.Hlg -> "HLG"
    Hdr.DolbyVision -> "Dolby Vision"
    Hdr.None -> "SDR"
}

/** Voies sonores : « 2.0 », « 5.1 », « 7.1 » ; `null` si inconnues. */
internal fun channelsLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1.0"
    count == 2 -> "2.0"
    count == 6 -> "5.1"
    count == 8 -> "7.1"
    else -> "$count ch"
}

/** « 48 kHz », « 44,1 kHz » ; `null` si inconnue. */
internal fun sampleRateLabel(hz: Int, locale: Locale): String? =
    if (hz > 0) DecimalFormat("0.#", DecimalFormatSymbols(locale)).format(hz / 1000.0) + " kHz" else null

/** « 18,4 Mb/s », « 640 kb/s » ; `null` si inconnu ou nul. */
internal fun bitrateLabel(bps: Long, locale: Locale): String? = when {
    bps <= 0 -> null
    bps >= 1_000_000 -> DecimalFormat("0.0", DecimalFormatSymbols(locale)).format(bps / 1_000_000.0) + " Mb/s"
    else -> DecimalFormat("0", DecimalFormatSymbols(locale)).format(bps / 1_000.0) + " kb/s"
}

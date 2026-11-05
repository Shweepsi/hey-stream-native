package io.github.shweepsi.heystream.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/** Plage dynamique de l'image : de quoi dire à l'écran si le HDR est bien transmis. */
enum class Hdr { None, Hdr10, Hlg, DolbyVision }

/** Piste vidéo en cours de lecture, telle que le lecteur l'a ouverte. Zéro ou `null` : inconnu. */
data class VideoInfo(
    val mime: String? = null,
    val codecs: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val hdr: Hdr = Hdr.None,
    /** Décodeur qui l'a ouverte (matériel, ou logiciel de secours). */
    val decoder: String? = null,
)

/** Piste audio en cours de lecture. */
data class AudioInfo(
    val mime: String? = null,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val decoder: String? = null,
)

/**
 * Écran « infos du flux » (appui long sur OK dans le lecteur) : ce que le lecteur lit vraiment,
 * pour voir d'un coup d'œil le codec, la résolution, le HDR, le débit, les images perdues et le
 * décodeur, sans outil. Rien ici ne désigne le flux (jamais d'adresse, ni d'identifiant).
 */
data class StreamInfo(
    val video: VideoInfo? = null,
    val audio: AudioInfo? = null,
    /** Débit mesuré sur le réseau (bit/s) ; 0 : pas encore estimé. */
    val bandwidthBps: Long = 0,
    /** Images vidéo non affichées à temps depuis l'ouverture du flux. */
    val droppedFrames: Int = 0,
)

/** HDR d'après les métadonnées de couleur du flux (ST 2084 : HDR10, HLG) ou son type (Dolby Vision). */
@OptIn(UnstableApi::class)
internal fun hdrOf(format: Format): Hdr = when {
    format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> Hdr.DolbyVision
    format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> Hdr.Hdr10
    format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> Hdr.Hlg
    else -> Hdr.None
}

/** Piste vidéo décrite par [format] ; le décodeur déjà connu ([decoder]) est gardé. */
@OptIn(UnstableApi::class)
internal fun videoInfoOf(format: Format, decoder: String? = null): VideoInfo = VideoInfo(
    mime = format.sampleMimeType,
    codecs = format.codecs,
    width = format.width.coerceAtLeast(0),
    height = format.height.coerceAtLeast(0),
    frameRate = format.frameRate.takeIf { it > 0f } ?: 0f,
    bitrate = format.bitrate.coerceAtLeast(0),
    hdr = hdrOf(format),
    decoder = decoder,
)

/** Piste audio décrite par [format] ; le décodeur déjà connu ([decoder]) est gardé. */
@OptIn(UnstableApi::class)
internal fun audioInfoOf(format: Format, decoder: String? = null): AudioInfo = AudioInfo(
    mime = format.sampleMimeType,
    channels = format.channelCount.coerceAtLeast(0),
    sampleRate = format.sampleRate.coerceAtLeast(0),
    bitrate = format.bitrate.coerceAtLeast(0),
    decoder = decoder,
)

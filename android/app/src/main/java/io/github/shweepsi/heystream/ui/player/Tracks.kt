package io.github.shweepsi.heystream.ui.player

import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import java.util.Locale

/** Une piste du flux : groupe et rang dans `Player.currentTracks`. */
@Immutable
data class TrackKey(val group: Int, val track: Int)

/** Piste audio ou de sous-titres proposée au choix. */
@Immutable
data class TrackOption(
    val key: TrackKey,
    /** Code de langue du flux (« fr », « eng »), absent ou indéterminé sinon. */
    val language: String?,
    /** Nom donné par le flux (« Audiodescription »). */
    val label: String?,
    /** Nombre de canaux audio (6 : « 5.1 »), 0 si inconnu. */
    val channels: Int,
    /** Rang parmi les pistes du même type, à partir de 1 (« Piste 2 »). */
    val number: Int,
    val selected: Boolean,
)

/** Pistes audio et de sous-titres du flux en cours. */
@Immutable
data class TrackLists(
    val audio: List<TrackOption> = emptyList(),
    val text: List<TrackOption> = emptyList(),
) {
    /** Aucun sous-titre affiché. */
    val textOff: Boolean get() = text.none { it.selected }
}

/** Pistes lisibles par l'appareil, dans l'ordre du flux. */
fun trackLists(tracks: Tracks): TrackLists =
    TrackLists(audio = options(tracks, C.TRACK_TYPE_AUDIO), text = options(tracks, C.TRACK_TYPE_TEXT))

private fun options(tracks: Tracks, type: Int): List<TrackOption> {
    val result = ArrayList<TrackOption>()
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != type) return@forEachIndexed
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            result += TrackOption(
                key = TrackKey(groupIndex, index),
                language = format.language?.takeUnless { it.isBlank() || it.equals(C.LANGUAGE_UNDETERMINED, ignoreCase = true) },
                label = format.label?.trim()?.takeIf { it.isNotEmpty() },
                channels = if (format.channelCount == androidx.media3.common.Format.NO_VALUE) 0 else format.channelCount,
                number = result.size + 1,
                selected = group.isTrackSelected(index),
            )
        }
    }
    return result
}

/** Choisit une piste : elle remplace le choix automatique pour son type. */
fun selectTrack(parameters: TrackSelectionParameters, tracks: Tracks, key: TrackKey): TrackSelectionParameters {
    val group = tracks.groups.getOrNull(key.group) ?: return parameters
    if (key.track !in 0 until group.length) return parameters
    return parameters.buildUpon()
        .setTrackTypeDisabled(group.type, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, key.track))
        .build()
}

/** « Désactivés » : plus aucun sous-titre. */
fun disableSubtitles(parameters: TrackSelectionParameters): TrackSelectionParameters =
    parameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()

/** Nom de la langue dans celle de l'app (« fre » → « Français »), `null` si inconnue. */
fun languageName(code: String?, locale: Locale): String? {
    code ?: return null
    val language = Locale.forLanguageTag(code.replace('_', '-'))
    val name = language.getDisplayLanguage(locale)
    // Un code inconnu revient tel quel : rien d'utile à montrer.
    if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** Disposition des canaux (« 5.1 »), seulement au-delà de la stéréo. */
fun channelLayout(channels: Int): String? = when (channels) {
    6 -> "5.1"
    8 -> "7.1"
    else -> null
}

/** Texte d'une piste : langue, nom du flux, canaux ; `null` si le flux ne dit rien. */
fun trackDescription(option: TrackOption, locale: Locale): String? {
    val language = languageName(option.language, locale)
    val label = option.label?.takeUnless { language != null && it.equals(language, ignoreCase = true) }
    val parts = listOfNotNull(language, label, channelLayout(option.channels))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

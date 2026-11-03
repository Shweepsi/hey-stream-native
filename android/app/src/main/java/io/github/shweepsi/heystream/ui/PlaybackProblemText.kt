package io.github.shweepsi.heystream.ui

import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.player.PlaybackProblem

/** Message de la raison pour laquelle la lecture s'est arrêtée. */
fun PlaybackProblem.message(): UiText = UiText(
    when (this) {
        PlaybackProblem.SubscriptionInUse -> R.string.playback_in_use
        PlaybackProblem.AccountRefused -> R.string.playback_refused
        PlaybackProblem.NotFound -> R.string.playback_not_found
        PlaybackProblem.Unsupported -> R.string.playback_unsupported
        PlaybackProblem.Offline -> R.string.playback_offline
    },
)

package io.github.shweepsi.heystream.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import io.github.shweepsi.heystream.core.RetryAction
import io.github.shweepsi.heystream.core.StopReason
import io.github.shweepsi.heystream.core.playbackRetry

/** Pourquoi la lecture s'est arrêtée, quand on sait le dire à l'utilisateur. */
enum class PlaybackProblem {
    /** Code 509 : l'abonnement est utilisé sur un autre appareil. */
    SubscriptionInUse,

    /** Codes 401 et 403 : compte expiré, suspendu ou refusé. */
    AccountRefused,

    /** Code 404 : le fournisseur ne connaît pas ce flux. */
    NotFound,

    /** L'appareil ne sait pas lire ce format : un nouvel essai n'y change rien. */
    Unsupported,

    /** Aucun réseau : la lecture reprend au retour de la connexion. */
    Offline,
}

/** Suite donnée à un échec de lecture. */
internal sealed interface Step {
    /** Rouvrir le flux dans [delayMs] millisecondes. */
    data class Retry(val delayMs: Long) : Step

    /**
     * Abandon. [problem] : ce qu'on dit à l'utilisateur (`null` : message
     * général). [networkLike] : panne de connexion sans réponse du
     * fournisseur, que le retour du réseau peut guérir (jamais un refus).
     */
    data class Stop(val problem: PlaybackProblem?, val networkLike: Boolean) : Step
}

/**
 * Décide de la suite après [error] (`null` : un flux en direct qui se termine).
 * [policy] : règle du cœur selon le code HTTP et le nombre de relances ;
 * [offline] : l'appareil n'a aucun réseau.
 */
internal fun nextStep(
    error: PlaybackException?,
    attempt: Int,
    offline: Boolean,
    policy: (status: Int?, attempt: Int) -> RetryAction = ::corePolicy,
): Step {
    // Le format ne se lira pas mieux au deuxième essai.
    if (isUnsupportedFormat(error)) return Step.Stop(PlaybackProblem.Unsupported, networkLike = false)
    val status = httpStatusOf(error)
    return when (val action = policy(status, attempt)) {
        is RetryAction.Retry -> Step.Retry(action.delaySecs.toLong() * 1000)
        is RetryAction.Stop -> Step.Stop(
            problem = when (action.reason) {
                StopReason.SUBSCRIPTION_IN_USE -> PlaybackProblem.SubscriptionInUse
                StopReason.ACCOUNT_REFUSED -> PlaybackProblem.AccountRefused
                StopReason.NOT_FOUND -> PlaybackProblem.NotFound
                StopReason.FAILED -> PlaybackProblem.Offline.takeIf { offline }
            },
            // Un refus du fournisseur ne passe pas avec le réseau ; une coupure, si.
            networkLike = status == null,
        )
    }
}

/**
 * Règle de relance du cœur. Sans bibliothèque native (tests) : les mêmes
 * essais qu'avant le cœur (1, 2, 4, 8 s, six fois), sans code HTTP.
 */
internal fun corePolicy(status: Int?, attempt: Int): RetryAction =
    runCatching { playbackRetry(status?.toUShort(), attempt.toUInt()) }.getOrElse {
        if (attempt >= 6) {
            RetryAction.Stop(StopReason.FAILED)
        } else {
            RetryAction.Retry(delaySecs = minOf(1 shl attempt, 8).toUInt())
        }
    }

/** Code HTTP de la réponse du fournisseur qui a fait échouer la lecture, s'il y en a un. */
@OptIn(UnstableApi::class)
internal fun httpStatusOf(error: Throwable?): Int? {
    var cause = error
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSES) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

/** Format que l'appareil ne sait pas décoder (ou au-delà de ses capacités). */
internal fun isUnsupportedFormat(error: PlaybackException?): Boolean = when (error?.errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    -> true
    else -> false
}

/** Nom du décodeur matériel qui a planté en cours de lecture, s'il est connu. */
@OptIn(UnstableApi::class)
internal fun failedDecoderOf(error: PlaybackException?): String? {
    if (error?.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return null
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSES) {
        if (cause is MediaCodecDecoderException) return cause.codecInfo?.name
        cause = cause.cause
    }
    return null
}

private const val MAX_CAUSES = 10

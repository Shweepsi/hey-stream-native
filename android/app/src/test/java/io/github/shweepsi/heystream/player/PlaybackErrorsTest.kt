package io.github.shweepsi.heystream.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.RetryAction
import io.github.shweepsi.heystream.core.StopReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Lecture du code HTTP et de la suite à donner, sur de vraies erreurs Media3 (aucun réseau). */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackErrorsTest {
    private fun http(status: Int) = HttpDataSource.InvalidResponseCodeException(
        status,
        null,
        null,
        emptyMap(),
        DataSpec(Uri.parse("http://panel.example/stream")),
        ByteArray(0),
    )

    private fun playbackError(cause: Throwable?, code: Int = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) =
        PlaybackException("lecture", cause, code)

    @Test
    fun statusIsReadFromTheCauseChain() {
        assertEquals(509, httpStatusOf(playbackError(http(509))))
        // Enveloppé dans une autre erreur d'entrée-sortie.
        assertEquals(404, httpStatusOf(playbackError(IOException("enveloppe", http(404)))))
        assertNull(httpStatusOf(playbackError(IOException("coupure"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertNull(httpStatusOf(playbackError(null, PlaybackException.ERROR_CODE_UNSPECIFIED)))
        assertNull(httpStatusOf(null))
    }

    @Test
    fun onlyDecodingFormatErrorsAreUnsupported() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )) {
            assertTrue(isUnsupportedFormat(playbackError(null, code)))
        }
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )) {
            assertFalse(isUnsupportedFormat(playbackError(null, code)))
        }
        assertFalse(isUnsupportedFormat(null))
    }

    @Test
    fun noDecoderIsNamedWithoutItsInfo() {
        // Un décodeur qui plante sans qu'on connaisse son nom : rien à écarter.
        val crashed = playbackError(MediaCodecDecoderException(IllegalStateException("codec"), null), PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertNull(failedDecoderOf(crashed))
        // Une autre erreur ne désigne aucun décodeur.
        assertNull(failedDecoderOf(playbackError(MediaCodecDecoderException(IllegalStateException("codec"), null))))
        assertNull(failedDecoderOf(null))
    }

    @Test
    fun theCoreRuleDecidesAndTheStatusReachesIt() {
        val seen = mutableListOf<Pair<Int?, Int>>()
        val policy = { status: Int?, attempt: Int ->
            seen += status to attempt
            when (status) {
                509 -> RetryAction.Stop(StopReason.SUBSCRIPTION_IN_USE)
                401, 403 -> RetryAction.Stop(StopReason.ACCOUNT_REFUSED)
                404 -> if (attempt == 0) RetryAction.Retry(1u) else RetryAction.Stop(StopReason.NOT_FOUND)
                else -> if (attempt >= 6) RetryAction.Stop(StopReason.FAILED) else RetryAction.Retry(8u)
            }
        }
        assertEquals(Step.Retry(8_000), nextStep(playbackError(http(503)), 2, offline = false, policy))
        assertEquals(
            Step.Stop(PlaybackProblem.SubscriptionInUse, networkLike = false),
            nextStep(playbackError(http(509)), 0, offline = false, policy),
        )
        assertEquals(
            Step.Stop(PlaybackProblem.AccountRefused, networkLike = false),
            nextStep(playbackError(http(403)), 3, offline = true, policy),
        )
        assertEquals(Step.Retry(1_000), nextStep(playbackError(http(404)), 0, offline = false, policy))
        assertEquals(
            Step.Stop(PlaybackProblem.NotFound, networkLike = false),
            nextStep(playbackError(http(404)), 1, offline = false, policy),
        )
        assertEquals(listOf<Pair<Int?, Int>>(503 to 2, 509 to 0, 403 to 3, 404 to 0, 404 to 1), seen)
    }

    @Test
    fun givingUpWithoutNetworkSaysSoAndWaitsForTheNetwork() {
        val exhausted = { _: Int?, _: Int -> RetryAction.Stop(StopReason.FAILED) }
        val cut = playbackError(IOException("coupure"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(
            Step.Stop(PlaybackProblem.Offline, networkLike = true),
            nextStep(cut, 6, offline = true, exhausted),
        )
        // Connexion échouée sur un réseau présent : message général, mais le retour du réseau relancera.
        assertEquals(Step.Stop(null, networkLike = true), nextStep(cut, 6, offline = false, exhausted))
        // Un direct qui se termine sur un réseau présent n'est pas une panne de connexion ; sans réseau, si.
        assertEquals(Step.Stop(null, networkLike = false), nextStep(null, 6, offline = false, exhausted))
        assertEquals(Step.Stop(PlaybackProblem.Offline, networkLike = true), nextStep(null, 6, offline = true, exhausted))
        // Un serveur qui répond 500 sans cesse n'est pas une panne de réseau, réseau coupé ou non.
        assertEquals(Step.Stop(null, networkLike = false), nextStep(playbackError(http(500)), 6, offline = false, exhausted))
        // Un décodeur qui plante, un fichier absent : rien que le réseau puisse guérir.
        val decoder = playbackError(IllegalStateException("codec"), PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertEquals(Step.Stop(null, networkLike = false), nextStep(decoder, 6, offline = false, exhausted))
    }

    @Test
    fun anUnsupportedFormatStopsWithoutAskingTheCore() {
        val never = { _: Int?, _: Int -> error("le cœur ne doit pas être interrogé") }
        assertEquals(
            Step.Stop(PlaybackProblem.Unsupported, networkLike = false),
            nextStep(playbackError(null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED), 0, offline = false, never),
        )
    }

    @Test
    fun onlyAConnectivityFailureWaitsForTheNetwork() {
        val timeout = playbackError(IOException("délai"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        val failed = playbackError(IOException("connexion"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertTrue(isConnectivityFailure(timeout, offline = false))
        assertTrue(isConnectivityFailure(failed, offline = false))
        // Sans réseau, toute erreur sans réponse du fournisseur en est une (décodeur, flux terminé…).
        assertTrue(isConnectivityFailure(playbackError(null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), offline = true))
        assertTrue(isConnectivityFailure(null, offline = true))
        // Réseau présent : fichier absent, décodeur, flux terminé.
        assertFalse(isConnectivityFailure(playbackError(null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND), offline = false))
        assertFalse(isConnectivityFailure(playbackError(null, PlaybackException.ERROR_CODE_DECODING_FAILED), offline = false))
        assertFalse(isConnectivityFailure(null, offline = false))
        // Une réponse du fournisseur n'est jamais une panne de connexion.
        assertFalse(isConnectivityFailure(playbackError(http(503)), offline = true))
        assertFalse(isConnectivityFailure(playbackError(http(509)), offline = false))
    }
}

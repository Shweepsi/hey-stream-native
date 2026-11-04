package io.github.shweepsi.heystream.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import io.github.shweepsi.heystream.core.RetryAction
import io.github.shweepsi.heystream.core.StopReason
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/** Compte les ouvertures de flux du lecteur (passages au chargement). */
internal class Opens : Player.Listener {
    var count = 0
        private set

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING) count += 1
    }
}

/**
 * Fait tourner le fil principal (Robolectric) jusqu'à [done], sans avancer
 * l'horloge : le cœur répond sur d'autres fils.
 */
internal fun runMainUntil(timeoutMs: Long = 5_000, done: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        check(System.currentTimeMillis() < end) { "Condition jamais atteinte" }
        Thread.sleep(5)
    }
}

/** Laisse le temps aux réponses en cours d'arriver (rien ne doit changer). */
internal fun settleMain(ms: Long = 300) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
}

/** Comme [runMainUntil], en avançant l'horloge (lecteur, reconnexions). */
internal fun advanceMainUntil(maxSimulatedMs: Long = 30_000, done: () -> Boolean) {
    var elapsed = 0L
    while (!done()) {
        check(elapsed < maxSimulatedMs) { "Condition jamais atteinte" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        elapsed += 10
        Thread.sleep(2)
    }
}

/**
 * Règle de relance des tests : 1, 2, 4, 8, 8, 8 s puis abandon, sans code HTTP.
 * Sous Robolectric la bibliothèque native du cœur n'est pas là ; la vraie règle
 * est testée en Rust (`core/tests/playback.rs`).
 */
internal fun testRetryPolicy(status: Int?, attempt: Int): RetryAction =
    if (attempt >= 6) RetryAction.Stop(StopReason.FAILED) else RetryAction.Retry(minOf(1 shl attempt, 8).toUInt())

/** Lecteur de test : règle ci-dessus, et réseau présent sauf si [offline] dit le contraire. */
internal fun testController(context: Context, offline: () -> Boolean = { false }) =
    PlayerController(context, retryPolicy = ::testRetryPolicy, offline = offline)

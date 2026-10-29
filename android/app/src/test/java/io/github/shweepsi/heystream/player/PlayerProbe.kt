package io.github.shweepsi.heystream.player

import android.os.Looper
import androidx.media3.common.Player
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

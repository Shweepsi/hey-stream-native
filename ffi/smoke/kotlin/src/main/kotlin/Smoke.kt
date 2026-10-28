import io.github.shweepsi.heystream.core.*
import java.nio.file.Files

fun main() {
    println("cœur " + coreVersion())
    val dir = Files.createTempDirectory("hs-kt").toString()
    // Serveur injoignable : on vérifie le chargement, le cache et les erreurs typées.
    val core = HeyStreamCore("http://127.0.0.1:9", "u", "p", dir)
    println("catégories en cache : " + core.categories(Section.LIVE).size)
    check(runCatching { core.authenticate() }.exceptionOrNull() is HeyStreamException.Network)
    check(runCatching { core.setPin("12a4", null) }.exceptionOrNull() is HeyStreamException.InvalidPin)
    println("erreurs typées : OK")
    core.setPin("1234", null)
    core.unlockParental("1234")
    check(core.isParentalUnlocked())
    check(core.streamUrl(StreamKind.LIVE, 5L, null) == "http://127.0.0.1:9/live/u/p/5.ts")
    check(core.liveUrl(5L, LiveFormat.TS) == "http://127.0.0.1:9/live/u/p/5.ts")
    check(core.accountInfo() == null)
    check(core.cachedNow(listOf(5L)).isEmpty())
    check(core.continueWatching(PlaybackKind.MOVIE, 10u).isEmpty())
    core.setWatched(PlaybackKind.MOVIE, 5L, null, true)
    check(core.isWatched(PlaybackKind.MOVIE, 5L))
    core.signOut()
    check(runCatching { core.categories(Section.LIVE) }.exceptionOrNull() is HeyStreamException.SignedOut)
    println("liaisons Kotlin : OK")
    core.close()
}

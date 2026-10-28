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
    println("liaisons Kotlin : OK")
    core.close()
}

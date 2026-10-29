package io.github.shweepsi.heystream.data

import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.ChannelNow
import io.github.shweepsi.heystream.core.Episode
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.LiveFormat
import io.github.shweepsi.heystream.core.MovieInfo
import io.github.shweepsi.heystream.core.NoHandle
import io.github.shweepsi.heystream.core.NowNext
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Resume
import io.github.shweepsi.heystream.core.ResumeEntry
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SeriesInfo
import io.github.shweepsi.heystream.core.StreamKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.RuntimeEnvironment

/**
 * Faux cœur, sans bibliothèque native ni fournisseur : un catalogue en
 * mémoire et le verrou parental. Une entrée adulte n'est visible que verrou
 * ouvert (comme `item` du cœur). Les flux pointent vers des fichiers absents
 * de l'appareil de test : le lecteur ne contacte rien.
 */
internal class ParentalFakeCore : HeyStreamCore(NoHandle) {
    val items = ConcurrentHashMap<Pair<Section, Long>, CatalogItem>()
    val adult: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val movieInfos = ConcurrentHashMap<Long, MovieInfo>()
    val seriesInfos = ConcurrentHashMap<Long, SeriesInfo>()

    @Volatile
    var unlocked = true

    /** Reverrouillage retenu jusqu'à son ouverture (cœur occupé, par exemple par une mise à jour). */
    @Volatile
    var relockGate: CountDownLatch? = null

    /** Reverrouillages commencés (le dernier attend peut-être [relockGate]). */
    val relocksStarted = AtomicInteger()

    fun add(section: Section, id: Long, name: String, adult: Boolean = false) {
        items[section to id] = CatalogItem(
            section = section,
            id = id,
            name = name,
            image = null,
            categoryId = if (adult) "adultes" else "tous",
            extension = if (section == Section.LIVE) null else "mkv",
            epgChannelId = null,
            rating = null,
            plot = null,
            number = null,
            progress = null,
            watched = false,
        )
        if (adult) this.adult += id
    }

    override fun item(section: Section, id: Long): CatalogItem? =
        items[section to id]?.takeIf { unlocked || it.id !in adult }

    override fun isParentalUnlocked(): Boolean = unlocked

    override fun lockParental() {
        val gate = relockGate
        relocksStarted.incrementAndGet()
        gate?.await(10, TimeUnit.SECONDS)
        unlocked = false
    }

    override fun hasPin(): Boolean = true

    override fun streamUrl(kind: StreamKind, id: Long, extension: String?): String = "file:///absent/$id.$extension"

    override fun liveUrl(streamId: Long, preferred: LiveFormat): String = "file:///absent/live/$streamId.ts"

    override fun movieInfo(streamId: Long): MovieInfo = checkNotNull(movieInfos[streamId])

    override fun seriesInfo(seriesId: Long): SeriesInfo = checkNotNull(seriesInfos[seriesId])

    override fun progress(kind: PlaybackKind, id: Long): Long? = null

    override fun saveProgress(resume: Resume) = Unit

    override fun addRecent(section: Section, id: Long) = Unit

    override fun isFavorite(section: Section, id: Long): Boolean = false

    override fun isWatched(kind: PlaybackKind, id: Long): Boolean = false

    override fun continueWatching(kind: PlaybackKind?, limit: UInt): List<ResumeEntry> = emptyList()

    override fun nextEpisode(seriesId: Long, episodeId: Long): Episode? = null

    override fun categories(section: Section): List<CategoryEntry> = emptyList()

    override fun favorites(section: Section, offset: UInt, limit: UInt): List<CatalogItem> = emptyList()

    override fun recents(section: Section, limit: UInt): List<CatalogItem> = emptyList()

    override fun count(section: Section, categoryId: String?): ULong = 0u

    override fun nowNext(streamId: Long): NowNext = NowNext(now = null, next = null)

    override fun cachedNow(streamIds: List<Long>): List<ChannelNow> = emptyList()
}

/** Session ouverte sur [core] (le cœur réel s'ouvre avec la bibliothèque native : remplacé ici). */
internal fun fakeSession(core: HeyStreamCore): CoreSession {
    val context = RuntimeEnvironment.getApplication()
    val session = CoreSession(context, CredentialStore(context), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    CoreSession::class.java.getDeclaredField("core").apply { isAccessible = true }.set(session, core)
    return session
}

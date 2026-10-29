package io.github.shweepsi.heystream.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Resume
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SeriesInfo
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Nombre d'entrées « Continuer » lues pour retrouver l'épisode en cours. */
private const val CONTINUE_LIMIT = 200u

/**
 * Fiche série : le cache s'affiche aussitôt ; la fiche du fournisseur
 * apporte saisons et épisodes (et permet au cœur de savoir quand la série
 * est entièrement vue). L'état de chaque épisode (vu, commencé) est lu dans
 * le cache, hors du fil principal.
 */
class SeriesDetailViewModel(private val session: CoreSession, private val id: Long) : ViewModel() {
    private val _state = MutableStateFlow(SeriesDetailState())
    val state: StateFlow<SeriesDetailState> = _state.asStateFlow()

    private var item: CatalogItem? = null
    private var itemLoaded = false
    private var info: SeriesInfo? = null
    private var infoFailure: Throwable? = null
    private var rows: List<EpisodeRow> = emptyList()
    private var resumeEpisodeId: Long? = null
    private var infoJob: Job? = null
    private var userDataJob: Job? = null

    /** Ce que le cache dit de l'utilisateur pour cette série. */
    private class UserData(
        val item: CatalogItem?,
        val favorite: Boolean,
        val rows: List<EpisodeRow>,
        val resume: Resume?,
        /** Série cachée par le contrôle parental (même règle que [visibleItem]). */
        val lockedOut: Boolean = false,
    )

    /** Versions du cache déjà lues : un changement relit favori, vus et reprise. */
    private var seenVersions = session.userDataVersion.value to session.catalogVersion.value

    init {
        reloadUserData()
        fetchInfo(fresh = false)
        viewModelScope.launch {
            // Reverrouillage (l'app quittée), même sous le lecteur : relue tout
            // de suite, la fiche d'une série désormais cachée ne se montre plus au retour.
            session.relocks.drop(1).collect { reloadUserData() }
        }
    }

    /** Appelé par l'écran visible quand le cache change (pas pendant la lecture). */
    fun onDataVersion(userData: Long, catalog: Long) {
        val versions = userData to catalog
        if (versions != seenVersions) {
            seenVersions = versions
            reloadUserData()
        }
    }

    /** Favori, vus, reprise (au retour du lecteur, après un changement). */
    fun reloadUserData() {
        userDataJob?.cancel()
        userDataJob = viewModelScope.launch {
            val known = info
            val data = try {
                session.withCore { core -> readUserData(core, known) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                UserData(item, favorite = _state.value.favorite, rows = rows, resume = null)
            }
            if (data.lockedOut) {
                lockOut()
                publish()
                return@launch
            }
            item = data.item
            itemLoaded = true
            rows = data.rows
            resumeEpisodeId = data.resume?.id
            _state.update { it.copy(favorite = data.favorite) }
            publish()
        }
    }

    fun retry() {
        infoFailure = null
        _state.update { it.copy(error = null, episodesError = null, episodesLoading = true, loading = it.header == null) }
        fetchInfo(fresh = true)
    }

    fun selectSeason(season: Long) {
        _state.update { state ->
            if (state.selectedSeason == season) state else state.copy(selectedSeason = season, episodes = rows.filter { it.season == season })
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val favorite = runCatching { session.withCore { it.toggleFavorite(Section.SERIES, id) } }.getOrNull()
                ?: return@launch
            _state.update { it.copy(favorite = favorite) }
            session.notifyUserDataChanged()
        }
    }

    /** Toute la série vue ou non vue : chacun de ses épisodes connus. */
    fun toggleSeriesWatched() {
        val episodes = rows.map { it.id }
        if (episodes.isEmpty()) return
        val watched = !_state.value.watched
        viewModelScope.launch {
            runCatching {
                session.withCore { core ->
                    episodes.forEach { episode -> core.setWatched(PlaybackKind.EPISODE, episode, id, watched) }
                }
            }
            // L'écran visible relit alors l'état des épisodes (onDataVersion).
            session.notifyUserDataChanged()
        }
    }

    /** Appui long ou touche MENU sur un épisode. */
    fun toggleEpisodeWatched(episodeId: Long) {
        val row = rows.firstOrNull { it.id == episodeId } ?: return
        viewModelScope.launch {
            runCatching { session.withCore { it.setWatched(PlaybackKind.EPISODE, episodeId, id, !row.watched) } }
            session.notifyUserDataChanged()
        }
    }

    private fun fetchInfo(fresh: Boolean) {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            try {
                // `fresh` : « Réessayer » redemande toujours la fiche. Contenu
                // peut-être verrouillé : rien n'est demandé au fournisseur.
                info = session.withCore { core ->
                    core.visibleItem(Section.SERIES, id)
                    SeriesInfoMemory.load(core, id, fresh)
                }
                infoFailure = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                infoFailure = failure
            }
            if (info != null) {
                // Épisodes connus : leur état est lu dans le cache.
                reloadUserData()
            } else {
                publish()
            }
        }
    }

    private fun readUserData(core: HeyStreamCore, known: SeriesInfo?): UserData {
        val cachedItem = core.item(Section.SERIES, id)
        if (lockedOut(cachedItem, core.isParentalUnlocked())) {
            return UserData(null, favorite = false, rows = emptyList(), resume = null, lockedOut = true)
        }
        val favorite = core.isFavorite(Section.SERIES, id)
        val resume = core.continueWatching(PlaybackKind.EPISODE, CONTINUE_LIMIT)
            .firstOrNull { it.resume.seriesId == id }?.resume
        val rows = if (known == null) {
            emptyList()
        } else {
            val ids = known.seasons.flatMap { season -> season.episodes.map { it.id } }
            val watched = ids.filterTo(HashSet()) { core.isWatched(PlaybackKind.EPISODE, it) }
            val positions = HashMap<Long, Long>()
            ids.forEach { episode -> core.progress(PlaybackKind.EPISODE, episode)?.let { positions[episode] = it } }
            episodeRows(known, watched, positions, resume)
        }
        return UserData(cachedItem, favorite, rows, resume)
    }

    /**
     * Série verrouillée : fiche, saisons et épisodes oubliés, une demande en
     * cours ne les ramènera pas, et l'écran montre le verrouillage.
     */
    private fun lockOut() {
        infoJob?.cancel()
        info = null
        infoFailure = HeyStreamException.ParentalLock()
        item = null
        itemLoaded = true
        rows = emptyList()
        resumeEpisodeId = null
    }

    private fun publish() {
        if (!itemLoaded && info == null) return
        val header = seriesHeader(item, info)
        if (header == null) {
            val failure = infoFailure
            _state.value = if (failure == null) {
                SeriesDetailState(loading = true)
            } else {
                SeriesDetailState(loading = false, error = failure.toUiText(), episodesLoading = false)
            }
            return
        }
        val known = info
        val seasons = known?.seasons?.map { it.number }?.distinct().orEmpty()
        val target = playTarget(rows, resumeEpisodeId)
        _state.update { state ->
            val season = chooseSeason(seasons, state.selectedSeason, target)
            state.copy(
                header = header,
                loading = false,
                error = null,
                watched = if (rows.isNotEmpty()) rows.all { it.watched } else item?.watched ?: false,
                episodesLoading = known == null && infoFailure == null,
                episodesError = if (known == null) infoFailure?.toUiText() else null,
                seasons = seasons,
                selectedSeason = season,
                episodes = rows.filter { it.season == season },
                playTarget = target,
            )
        }
    }
}

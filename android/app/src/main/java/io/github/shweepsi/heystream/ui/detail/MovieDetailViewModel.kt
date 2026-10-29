package io.github.shweepsi.heystream.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.MovieInfo
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fiche film : le cache s'affiche aussitôt, la fiche du fournisseur
 * l'enrichit ensuite (résumé, année, durée, note). En cas d'échec réseau, le
 * cache reste affiché.
 */
class MovieDetailViewModel(private val session: CoreSession, private val id: Long) : ViewModel() {
    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    private var cached: CachedMovie? = null
    private var info: MovieInfo? = null
    private var infoFailure: Throwable? = null
    private var infoJob: Job? = null
    private var userDataJob: Job? = null

    /** Ce que le cache connaît du film et de l'utilisateur. */
    private class CachedMovie(
        val item: CatalogItem?,
        val favorite: Boolean,
        val watched: Boolean,
        val positionMs: Long?,
    )

    /** Versions du cache déjà lues : un changement relit favori, vu et reprise. */
    private var seenVersions = session.userDataVersion.value to session.catalogVersion.value

    init {
        reloadUserData()
        fetchInfo()
    }

    /** Appelé par l'écran visible quand le cache change (pas pendant la lecture). */
    fun onDataVersion(userData: Long, catalog: Long) {
        val versions = userData to catalog
        if (versions != seenVersions) {
            seenVersions = versions
            reloadUserData()
        }
    }

    /** Favori, vu, reprise (au retour du lecteur, après un changement). */
    fun reloadUserData() {
        userDataJob?.cancel()
        userDataJob = viewModelScope.launch {
            cached = try {
                session.withCore { core ->
                    CachedMovie(
                        item = core.item(Section.MOVIES, id),
                        favorite = core.isFavorite(Section.MOVIES, id),
                        watched = core.isWatched(PlaybackKind.MOVIE, id),
                        positionMs = core.progress(PlaybackKind.MOVIE, id),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Cache illisible : la fiche du fournisseur suffit.
                CachedMovie(null, favorite = false, watched = false, positionMs = null)
            }
            publish()
        }
    }

    /** « Réessayer » de l'écran d'erreur. */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        reloadUserData()
        fetchInfo()
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val favorite = runCatching { session.withCore { it.toggleFavorite(Section.MOVIES, id) } }.getOrNull()
                ?: return@launch
            _state.update { state -> state.copy(movie = state.movie?.copy(favorite = favorite)) }
            session.notifyUserDataChanged()
        }
    }

    fun toggleWatched() {
        val watched = _state.value.movie?.watched ?: return
        viewModelScope.launch {
            // L'écran visible relit alors la fiche (onDataVersion).
            runCatching { session.withCore { it.setWatched(PlaybackKind.MOVIE, id, null, !watched) } }
                .onSuccess { session.notifyUserDataChanged() }
        }
    }

    private fun fetchInfo() {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            try {
                info = session.withCore { it.movieInfo(id) }
                infoFailure = null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                infoFailure = failure
            }
            publish()
        }
    }

    private fun publish() {
        val cache = cached ?: return
        val infoDone = info != null || infoFailure != null
        val header = movieHeader(cache.item, info)
        if (header == null) {
            _state.value = when {
                !infoDone -> MovieDetailState(loading = true)
                else -> MovieDetailState(loading = false, error = (infoFailure ?: NotFoundFailure).toUiText())
            }
            return
        }
        val movie = MovieDetail(
            header = header,
            progress = movieProgress(cache.item, cache.positionMs, header.durationSecs),
            watched = cache.item?.watched ?: cache.watched,
            favorite = cache.favorite,
        )
        _state.value = MovieDetailState(movie = movie, loading = false)
    }
}

/** Fiche vide (ni nom ni cache) : « contenu introuvable ». */
internal val NotFoundFailure: Throwable get() = HeyStreamException.NotFound()

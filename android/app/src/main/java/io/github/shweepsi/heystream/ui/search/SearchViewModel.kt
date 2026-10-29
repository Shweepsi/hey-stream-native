package io.github.shweepsi.heystream.ui.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.data.RefreshState
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Nombre de résultats demandés au cœur par section. */
const val SEARCH_LIMIT = 30

/** Attente après la dernière frappe avant de chercher. */
const val SEARCH_DEBOUNCE_MS = 300L

/** Longueur minimale d'une recherche (en caractères). */
const val SEARCH_MIN_CHARS = 2

/** Terme envoyé au cœur, ou `null` s'il est trop court pour chercher. */
fun searchTerm(raw: String): String? {
    val term = raw.trim()
    return term.takeIf { it.codePointCount(0, it.length) >= SEARCH_MIN_CHARS }
}

/** Un résultat : chaîne (ligne), film ou série (affiche). */
@Immutable
data class SearchHit(
    val section: Section,
    val id: Long,
    val name: String,
    val image: String?,
    /** Numéro du fournisseur (chaînes), absent s'il n'en donne pas. */
    val number: Long?,
    /** Avancement de lecture (films, séries), absent si rien n'est commencé. */
    val progress: Float?,
    val watched: Boolean,
) {
    /** Clé stable dans les listes et pour retrouver le focus au retour. */
    val key: String get() = "${section.name}:$id"
}

fun CatalogItem.toHit(): SearchHit = SearchHit(
    section = section,
    id = id,
    name = Formats.displayName(name),
    image = image,
    number = number,
    progress = progress?.takeIf { it.durationMs > 0 }?.let { Formats.fraction(it.positionMs, it.durationMs) },
    watched = watched,
)

/** Ce que montre la zone des résultats. */
sealed interface SearchResults {
    /** Moins de deux caractères : invitation à saisir. */
    data object Idle : SearchResults

    /** Aucun résultat pendant le téléchargement du catalogue. */
    data object Downloading : SearchResults

    data class Failed(val error: UiText) : SearchResults

    /** Résultats par section (listes vides : « aucun résultat »). */
    @Immutable
    data class Found(
        val term: String,
        val channels: List<SearchHit>,
        val movies: List<SearchHit>,
        val series: List<SearchHit>,
    ) : SearchResults {
        val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    }
}

/** Résultats d'une recherche, compte tenu d'un téléchargement du catalogue en cours. */
fun resultsOf(term: String, channels: List<SearchHit>, movies: List<SearchHit>, series: List<SearchHit>, downloading: Boolean): SearchResults {
    val found = SearchResults.Found(term, channels, movies, series)
    return if (found.isEmpty && downloading) SearchResults.Downloading else found
}

/**
 * Recherche par nom dans les trois sections. Le cœur exclut déjà le contenu
 * masqué et verrouillé, et ignore la casse et les accents.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val session: CoreSession) : ViewModel() {
    /** Texte du champ, lu et écrit sans délai (état Compose). */
    var query by mutableStateOf("")
        private set

    /** Dernier résultat ouvert : il reprend le focus au retour de sa fiche. */
    var lastOpened: String? = null
        private set

    private val term = snapshotFlow { query }
        .map(::searchTerm)
        .distinctUntilChanged()
        .debounce { if (it == null) 0L else SEARCH_DEBOUNCE_MS }

    private val downloading = session.refresh
        .map { it is RefreshState.Running }
        .distinctUntilChanged()

    val results: StateFlow<SearchResults> = combine(
        term,
        downloading,
        session.catalogVersion,
        session.userDataVersion,
    ) { term, downloading, _, _ -> term to downloading }
        .mapLatest { (term, downloading) -> if (term == null) SearchResults.Idle else search(term, downloading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.Idle)

    fun onQueryChange(value: String) {
        query = value
    }

    fun onOpen(hit: SearchHit) {
        lastOpened = hit.key
    }

    private suspend fun search(term: String, downloading: Boolean): SearchResults = try {
        val limit = SEARCH_LIMIT.toUInt()
        val (channels, movies, series) = session.withCore { core ->
            Triple(
                core.search(Section.LIVE, term, limit).map { it.toHit() },
                core.search(Section.MOVIES, term, limit).map { it.toHit() },
                core.search(Section.SERIES, term, limit).map { it.toHit() },
            )
        }
        resultsOf(term, channels, movies, series, downloading)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        SearchResults.Failed(failure.toUiText())
    }
}

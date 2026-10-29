package io.github.shweepsi.heystream.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.map
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.Progress
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.CatalogPaging
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.data.OffsetPagingSource
import io.github.shweepsi.heystream.data.RefreshState
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Résultat d'une lecture du cœur. */
private sealed interface VodLoad {
    data class Loaded(val data: VodData) : VodLoad

    data class Failed(val error: UiText) : VodLoad
}

/**
 * Films ou séries : catégories, « Continuer », « Favoris » et grille paginée.
 *
 * Le ViewModel vit dans l'entrée de navigation : en revenant d'une fiche, la
 * catégorie, la grille déjà chargée et sa position sont intactes. Les données
 * ne sont relues que pendant que l'écran est affiché (et 5 s après), pas
 * pendant la lecture d'un film.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel(private val session: CoreSession, val section: Section) : ViewModel() {
    private val retryTick = MutableStateFlow(0)
    private val userSelection = MutableStateFlow<CategoryKey?>(null)
    private val _pin = MutableStateFlow<PinRequest?>(null)

    /** Code PIN à demander avant d'ouvrir une catégorie verrouillée. */
    val pin: StateFlow<PinRequest?> = _pin.asStateFlow()

    /** Dernière catégorie ouverte : elle reste ouverte tant qu'elle existe. */
    private var previous: CategoryKey? = null

    /** Source de la grille affichée, invalidée quand les données changent. */
    @Volatile
    private var source: PagingSource<Int, CatalogItem>? = null

    private var paged: Pair<CategoryKey, Flow<PagingData<PosterUi>>>? = null
    private var pagedScope: CoroutineScope? = null

    private val loads: Flow<VodLoad> =
        combine(session.catalogVersion, session.userDataVersion, retryTick) { catalog, user, retry ->
            Triple(catalog, user, retry)
        }
            .mapLatest { load() }
            // Progression, vus, favoris : la page affichée est relue à sa place.
            .onEach { if (it is VodLoad.Loaded) source?.invalidate() }

    val state: StateFlow<VodUiState> =
        combine(loads, userSelection, session.refresh) { load, user, refresh ->
            val downloading = refresh is RefreshState.Running
            when (load) {
                is VodLoad.Failed -> VodUiState(section, loading = false, error = load.error, downloading = downloading)
                is VodLoad.Loaded -> {
                    val selected = resolveSelection(user, previous, load.data)
                    previous = selected
                    VodUiState(
                        section = section,
                        loading = false,
                        categories = categoriesOf(load.data),
                        selected = selected,
                        continueItems = load.data.continueItems,
                        downloading = downloading,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState(section))

    /**
     * Grille paginée d'une catégorie (« Favoris » ou fournisseur). Le même
     * flux est rendu tant que la catégorie ne change pas : ses pages restent
     * en cache au retour d'une fiche.
     */
    fun pagedItems(key: CategoryKey): Flow<PagingData<PosterUi>> {
        paged?.let { (current, flow) -> if (current == key) return flow }
        pagedScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        pagedScope = scope
        val flow = Pager(CatalogPaging.config) { pagingSource(key).also { source = it } }
            .flow
            .map { page -> page.map { posterOf(it) } }
            .cachedIn(scope)
        paged = key to flow
        return flow
    }

    /** OK sur une catégorie : l'ouvre, ou demande le code PIN si elle est verrouillée. */
    fun onCategoryClick(category: VodCategory) {
        if (!category.locked) {
            userSelection.value = category.key
            return
        }
        viewModelScope.launch {
            val hasPin = runCatching { session.withCore { it.hasPin() } }.getOrDefault(true)
            _pin.value = PinRequest(create = !hasPin, key = category.key)
        }
    }

    /** Code bon : le cœur a déverrouillé, la catégorie s'ouvre au rechargement. */
    fun onPinSuccess() {
        val request = _pin.value ?: return
        _pin.value = null
        userSelection.value = request.key
    }

    fun onPinDismiss() {
        _pin.value = null
    }

    /** Relit les catégories après une erreur. */
    fun retry() {
        retryTick.update { it + 1 }
    }

    /** Catalogue vide : nouveau téléchargement. */
    fun refreshCatalog() {
        session.refreshInBackground(force = true)
    }

    private fun pagingSource(key: CategoryKey): PagingSource<Int, CatalogItem> = when (key) {
        is CategoryKey.Provider -> OffsetPagingSource(
            total = { session.withCore { it.count(section, key.id).toInt() } },
            load = { offset, limit ->
                session.withCore { it.items(section, key.id, offset.toUInt(), limit.toUInt()) }
            },
        )
        CategoryKey.Favorites -> OffsetPagingSource(
            total = { session.withCore { countFavorites(it, section).toInt() } },
            load = { offset, limit ->
                session.withCore { it.favorites(section, offset.toUInt(), limit.toUInt()) }
            },
        )
        // « Continuer » n'est pas paginé (100 entrées au plus).
        CategoryKey.Continue -> OffsetPagingSource(total = { 0 }, load = { _, _ -> emptyList() })
    }

    private suspend fun load(): VodLoad = try {
        VodLoad.Loaded(session.withCore { core -> readData(core, section) })
    } catch (error: Throwable) {
        VodLoad.Failed(error.toUiText())
    }

    override fun onCleared() {
        pagedScope?.cancel()
    }
}

/**
 * Catégories (avec leur nombre d'entrées), « Continuer » et nombre de
 * favoris, en un seul passage dans le cœur (appel bloquant).
 */
internal fun readData(core: HeyStreamCore, section: Section): VodData {
    val categories = core.categories(section).map { category ->
        VodCategory(
            key = CategoryKey.Provider(category.id),
            name = category.name,
            // Jamais de compte sur une catégorie verrouillée (erreur du cœur).
            count = if (category.locked) null else runCatching { core.count(section, category.id).toLong() }.getOrNull(),
            locked = category.locked,
        )
    }
    val continueItems = core.continueWatching(playbackKindOf(section), CONTINUE_LIMIT.toUInt()).mapNotNull { entry ->
        val resume = entry.resume
        val id = if (section == Section.SERIES) resume.seriesId else resume.id
        // Absent : verrouillé ou retiré du catalogue.
        id?.let { core.item(section, it) }?.let { posterOf(it, Progress(resume.positionMs, resume.durationMs)) }
    }.distinctBy { it.id }
    return VodData(categories, continueItems, countFavorites(core, section))
}

/** Taille des lots lus pour compter les favoris. */
private const val FAVORITES_CHUNK = 500

/** Au-delà, les favoris ne sont plus comptés (lecture bornée). */
private const val FAVORITES_MAX = 10_000

/**
 * Nombre de favoris visibles d'une section. Le cœur n'expose pas de
 * compteur : ils sont lus par lots (souvent un seul).
 */
internal fun countFavorites(core: HeyStreamCore, section: Section): Long {
    var total = 0L
    while (total < FAVORITES_MAX) {
        val page = core.favorites(section, total.toUInt(), FAVORITES_CHUNK.toUInt()).size
        total += page
        if (page < FAVORITES_CHUNK) break
    }
    return total
}

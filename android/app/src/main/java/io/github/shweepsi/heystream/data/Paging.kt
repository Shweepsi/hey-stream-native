package io.github.shweepsi.heystream.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.HeyStreamCore
import io.github.shweepsi.heystream.core.Section
import kotlinx.coroutines.flow.Flow

/**
 * Listes longues (jusqu'à 100 000 entrées) lues page par page dans le cache
 * SQLite : jamais une liste complète en mémoire (docs/MATERIEL.md).
 */
object CatalogPaging {
    const val PAGE_SIZE = 60

    /** Pages de taille fixe (décalages alignés), au plus 10 pages en mémoire. */
    val config = PagingConfig(
        pageSize = PAGE_SIZE,
        initialLoadSize = PAGE_SIZE,
        prefetchDistance = PAGE_SIZE,
        enablePlaceholders = true,
        maxSize = PAGE_SIZE * 10,
        jumpThreshold = PAGE_SIZE * 3,
    )

    /** Entrées d'une section, ou d'une de ses catégories. */
    fun items(session: CoreSession, section: Section, categoryId: String?): Flow<PagingData<CatalogItem>> =
        Pager(config) {
            OffsetPagingSource(
                total = { session.withCore { it.count(section, categoryId).toInt() } },
                load = { offset, limit ->
                    session.withCore { it.items(section, categoryId, offset.toUInt(), limit.toUInt()) }
                },
            )
        }.flow
}

/**
 * Source paginée par décalage. Toutes les pages ont la même taille et
 * commencent à un multiple de cette taille : pas de doublon au défilement
 * vers le haut. [total] donne le nombre d'entrées, pour une barre de
 * défilement juste et des emplacements réservés. Il est obligatoire :
 * [CatalogPaging.config] permet les sauts, que Paging refuse à une source
 * sans total.
 */
class OffsetPagingSource<T : Any>(
    private val total: suspend () -> Int,
    private val load: suspend (offset: Int, limit: Int) -> List<T>,
) : PagingSource<Int, T>() {
    private var count: Int? = null

    override val jumpingSupported: Boolean get() = true

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val size = state.config.pageSize
        return ((anchor - size / 2).coerceAtLeast(0) / size) * size
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        val size = params.loadSize
        val offset = (params.key ?: 0).coerceAtLeast(0) / size * size
        val items = load(offset, size)
        val end = offset + items.size
        // Une page courte termine la liste ; le total lu à part (liste qui
        // change entre deux lectures) ne laisse ni trou ni entrée perdue.
        val known = (if (items.size < size) end else maxOf(count ?: total(), end)).also { count = it }
        LoadResult.Page(
            data = items,
            prevKey = if (offset == 0) null else offset - size,
            nextKey = if (items.size < size || end >= known) null else end,
            itemsBefore = offset,
            itemsAfter = (known - end).coerceAtLeast(0),
        )
    } catch (error: Throwable) {
        LoadResult.Error(error)
    }
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

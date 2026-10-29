package io.github.shweepsi.heystream.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.shweepsi.heystream.core.CatalogItem
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

    /** Favoris d'une section. */
    fun favorites(session: CoreSession, section: Section): Flow<PagingData<CatalogItem>> =
        Pager(config) {
            OffsetPagingSource(
                total = null,
                load = { offset, limit ->
                    session.withCore { it.favorites(section, offset.toUInt(), limit.toUInt()) }
                },
            )
        }.flow
}

/**
 * Source paginée par décalage. Toutes les pages ont la même taille et
 * commencent à un multiple de cette taille : pas de doublon au défilement
 * vers le haut. [total] (facultatif) donne le nombre d'entrées, pour une
 * barre de défilement juste et des emplacements réservés.
 */
class OffsetPagingSource<T : Any>(
    private val total: (suspend () -> Int)?,
    private val load: suspend (offset: Int, limit: Int) -> List<T>,
) : PagingSource<Int, T>() {
    private var count: Int? = null

    override val jumpingSupported: Boolean get() = total != null

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val size = state.config.pageSize
        return ((anchor - size / 2).coerceAtLeast(0) / size) * size
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        val size = params.loadSize
        val offset = (params.key ?: 0).coerceAtLeast(0) / size * size
        val items = load(offset, size)
        val known = total?.let { count ?: it().also { value -> count = value } }
        val end = offset + items.size
        LoadResult.Page(
            data = items,
            prevKey = if (offset == 0) null else offset - size,
            nextKey = if (items.size < size || (known != null && end >= known)) null else end,
            itemsBefore = if (known != null) offset else LoadResult.Page.COUNT_UNDEFINED,
            itemsAfter = if (known != null) (known - end).coerceAtLeast(0) else LoadResult.Page.COUNT_UNDEFINED,
        )
    } catch (error: Throwable) {
        LoadResult.Error(error)
    }
}

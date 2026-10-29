package io.github.shweepsi.heystream.ui.live

import androidx.compose.runtime.Immutable
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.NowNext
import io.github.shweepsi.heystream.core.Programme
import io.github.shweepsi.heystream.player.PlaybackStatus
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.components.PinPurpose

/** Catégorie du direct : Favoris et Récents en tête, puis celles du fournisseur. */
@Immutable
sealed interface CategoryKey {
    /** Clé de ligne (listes paresseuses) : une chaîne, enregistrable. */
    val listKey: String

    data object Favorites : CategoryKey {
        override val listKey: String get() = "favorites"
    }

    data object Recents : CategoryKey {
        override val listKey: String get() = "recents"
    }

    data class Provider(val id: String) : CategoryKey {
        override val listKey: String get() = "category:$id"
    }
}

/** Ligne du panneau des catégories. */
@Immutable
data class LiveCategory(
    val key: CategoryKey,
    /** Nom du fournisseur ; `null` pour Favoris et Récents (textes de l'app). */
    val name: String?,
    /** Nombre de chaînes ; `null` s'il est inconnu ou si la catégorie est verrouillée. */
    val count: Long?,
    /** Catégorie adulte verrouillée par le contrôle parental. */
    val locked: Boolean = false,
)

/** Panneau des catégories. */
@Immutable
sealed interface CategoriesUi {
    data object Loading : CategoriesUi

    data class Failed(val error: UiText) : CategoriesUi

    /** Favoris et Récents d'abord, puis les catégories du fournisseur. */
    data class Ready(val categories: List<LiveCategory>) : CategoriesUi {
        /**
         * Rien à montrer : aucune catégorie du fournisseur (catalogue pas encore
         * téléchargé, ou toutes masquées), ni favori ni récent.
         */
        val catalogEmpty: Boolean
            get() = categories.none { it.key is CategoryKey.Provider || (it.count ?: 0) > 0 }
    }
}

/** Programme (EPG court), en secondes Unix. */
@Immutable
data class LiveProgramme(val title: String, val start: Long, val stop: Long)

fun Programme.toLive() = LiveProgramme(title = title, start = start, stop = stop)

/** Chaîne de l'aperçu, avec ses programmes en cours et suivant (s'ils sont connus). */
@Immutable
data class LivePreview(
    val id: Long,
    val name: String,
    val number: Long? = null,
    val logo: String? = null,
    val now: LiveProgramme? = null,
    val next: LiveProgramme? = null,
)

fun LivePreview.withEpg(nowNext: NowNext): LivePreview =
    copy(now = nowNext.now?.toLive(), next = nowNext.next?.toLive())

/** Ce que l'aperçu montre de la lecture. */
@Immutable
sealed interface LivePlayback {
    data object Idle : LivePlayback

    data object Playing : LivePlayback

    /** Coupure : nouvelle tentative en cours. */
    data object Reconnecting : LivePlayback

    /** Lecture abandonnée ; [error] : erreur du cœur avant la lecture, sinon message générique. */
    data class Failed(val error: UiText? = null) : LivePlayback
}

/** Ouverture d'une catégorie adulte : code PIN à saisir (ou à créer). */
@Immutable
data class PinRequest(val categoryId: String, val purpose: PinPurpose)

/** État de l'écran du direct (sans les listes de chaînes, paginées à part). */
@Immutable
data class LiveUiState(
    val categories: CategoriesUi = CategoriesUi.Loading,
    val selected: CategoryKey? = null,
    /** Téléchargement du catalogue en cours. */
    val downloading: Boolean = false,
    /** Chaînes favorites (cœur en bout de ligne). */
    val favoriteIds: Set<Long> = emptySet(),
    /** Programme en cours des lignes visibles, seulement s'il est déjà en cache. */
    val nowTitles: Map<Long, String> = emptyMap(),
    val preview: LivePreview? = null,
    /** Le même lecteur occupe tout l'écran. */
    val fullscreen: Boolean = false,
    /** Plein écran : bandeau d'information (chaîne et programme). */
    val overlayVisible: Boolean = false,
    val playback: LivePlayback = LivePlayback.Idle,
    /** Confirmation brève (favori ajouté ou retiré). */
    val message: UiText? = null,
    val pinRequest: PinRequest? = null,
)

/** Actions de l'écran, pour un contenu sans état (et des captures sans cœur natif). */
interface LiveActions {
    fun onSelectCategory(category: LiveCategory) {}

    /** OK ou toucher sur une chaîne : aperçu, ou plein écran si elle y est déjà. */
    fun onChannelClick(channel: CatalogItem) {}

    /** Appui long, ou touche Menu : ajoute ou retire des favoris. */
    fun onToggleFavorite(channel: CatalogItem) {}

    /** Chaînes affichées (et voisines) : leur programme en cours, s'il est en cache. */
    fun onVisibleChannels(ids: List<Long>) {}

    /** Toucher sur la vidéo : plein écran depuis l'aperçu, bandeau en plein écran. */
    fun onVideoClick() {}

    /** Télécommande en plein écran : OK, haut ou bas. */
    fun onFullscreenKey(ok: Boolean) {}

    fun onExitFullscreen() {}

    fun onRetryPlayback() {}

    fun onReloadCategories() {}

    /** Catalogue vide : nouveau téléchargement. */
    fun onRefreshCatalog() {}
}

/** Nombre de récents affichés. */
const val RECENTS_LIMIT = 50

/**
 * Catégories du panneau : Favoris et Récents, puis celles du fournisseur
 * (ordre du cœur). Une catégorie verrouillée n'a pas de nombre.
 */
fun liveCategories(
    provider: List<CategoryEntry>,
    counts: Map<String, Long>,
    favorites: Int,
    recents: Int,
): List<LiveCategory> = buildList(provider.size + 2) {
    add(LiveCategory(CategoryKey.Favorites, name = null, count = favorites.toLong()))
    add(LiveCategory(CategoryKey.Recents, name = null, count = recents.toLong()))
    provider.forEach { entry ->
        add(
            LiveCategory(
                key = CategoryKey.Provider(entry.id),
                name = entry.name,
                count = if (entry.locked) null else counts[entry.id],
                locked = entry.locked,
            ),
        )
    }
}

/**
 * Catégorie ouverte à l'arrivée : celle de [preferred] (chaîne venue de la
 * recherche) si elle est ouverte, sinon Favoris s'il y en a, sinon la
 * première catégorie du fournisseur non verrouillée.
 */
fun defaultCategory(categories: List<LiveCategory>, preferred: String? = null): CategoryKey {
    if (preferred != null) {
        categories.firstOrNull { (it.key as? CategoryKey.Provider)?.id == preferred && !it.locked }?.let { return it.key }
    }
    val favorites = categories.firstOrNull { it.key == CategoryKey.Favorites }
    if ((favorites?.count ?: 0) > 0) return CategoryKey.Favorites
    categories.firstOrNull { it.key is CategoryKey.Provider && !it.locked }?.let { return it.key }
    val recents = categories.firstOrNull { it.key == CategoryKey.Recents }
    if ((recents?.count ?: 0) > 0) return CategoryKey.Recents
    return CategoryKey.Favorites
}

/**
 * Catégorie à garder après un rechargement : la même si elle existe encore
 * et reste ouverte (masquée ou verrouillée entre-temps : catégorie par défaut).
 */
fun keepCategory(current: CategoryKey?, categories: List<LiveCategory>): CategoryKey {
    if (current == null) return defaultCategory(categories)
    if (current !is CategoryKey.Provider) return current
    val entry = categories.firstOrNull { it.key == current }
    return if (entry != null && !entry.locked) current else defaultCategory(categories)
}

/** Effet d'un OK (ou d'un toucher) sur une chaîne. */
enum class ChannelClick { Preview, Fullscreen }

fun channelClick(previewId: Long?, clickedId: Long): ChannelClick =
    if (previewId == clickedId) ChannelClick.Fullscreen else ChannelClick.Preview

/** Ce que l'aperçu montre de [status]. */
fun livePlaybackOf(status: PlaybackStatus, hasPreview: Boolean, previewError: UiText?): LivePlayback = when {
    !hasPreview -> LivePlayback.Idle
    previewError != null -> LivePlayback.Failed(previewError)
    status.failed -> LivePlayback.Failed()
    status.reconnectAttempt > 0 -> LivePlayback.Reconnecting
    status.active -> LivePlayback.Playing
    else -> LivePlayback.Idle
}

/** Lignes dont on lit le programme en cache : les visibles et [margin] de chaque côté. */
fun visibleWindow(firstVisible: Int, lastVisible: Int, itemCount: Int, margin: Int = 4): IntRange {
    if (itemCount <= 0 || lastVisible < firstVisible) return IntRange.EMPTY
    val first = (firstVisible - margin).coerceAtLeast(0)
    val last = (lastVisible + margin).coerceAtMost(itemCount - 1)
    return if (first > last) IntRange.EMPTY else first..last
}

/**
 * Programmes en cours connus : ceux de [queried] remplacés par [found] (une
 * chaîne absente de [found] n'a rien en cache), les autres gardés tant que la
 * table reste petite.
 */
fun mergeNowTitles(
    previous: Map<Long, String>,
    queried: List<Long>,
    found: Map<Long, String>,
    max: Int = 300,
): Map<Long, String> {
    val keep = if (previous.size + found.size > max) emptyMap() else previous - queried.toSet()
    return keep + found
}

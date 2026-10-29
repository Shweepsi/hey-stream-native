package io.github.shweepsi.heystream.ui.vod

import androidx.compose.runtime.Immutable
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.PlaybackKind
import io.github.shweepsi.heystream.core.Progress
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.UiText

/** Catégorie de la colonne de gauche : « Continuer », « Favoris » ou une du fournisseur. */
@Immutable
sealed interface CategoryKey {
    /** Clé de liste, stable et unique (`LazyColumn`, état de défilement). */
    val listKey: String

    data object Continue : CategoryKey {
        override val listKey: String get() = "@continue"
    }

    data object Favorites : CategoryKey {
        override val listKey: String get() = "@favorites"
    }

    data class Provider(val id: String) : CategoryKey {
        override val listKey: String get() = "c:$id"
    }
}

/**
 * Ligne de catégorie. [name] est celui du fournisseur (`null` pour
 * « Continuer » et « Favoris », traduits à l'affichage) ; [count] est absent
 * pour une catégorie verrouillée.
 */
@Immutable
data class VodCategory(
    val key: CategoryKey,
    val name: String?,
    val count: Long?,
    val locked: Boolean = false,
)

/** Affiche de la grille, avec ce qu'en montre la carte. */
@Immutable
data class PosterUi(
    val id: Long,
    val title: String,
    val image: String?,
    /** Avancement (0 à 1) d'une lecture commencée ; `null` : pas de barre. */
    val progress: Float?,
    val watched: Boolean,
)

/** Données lues dans le cœur pour l'écran. */
@Immutable
data class VodData(
    /** Catégories du fournisseur, dans l'ordre du cœur (masquées exclues). */
    val categories: List<VodCategory>,
    /** « Continuer », du plus récent au plus ancien. */
    val continueItems: List<PosterUi>,
    val favoritesCount: Long,
)

/** État de l'écran Films / Séries. */
@Immutable
data class VodUiState(
    val section: Section,
    /** Premier chargement en cours. */
    val loading: Boolean = true,
    val error: UiText? = null,
    /** « Continuer », « Favoris », puis les catégories du fournisseur. */
    val categories: List<VodCategory> = emptyList(),
    /** Catégorie ouverte ; `null` quand il n'y en a aucune à ouvrir. */
    val selected: CategoryKey? = null,
    val continueItems: List<PosterUi> = emptyList(),
    /** Téléchargement du catalogue en cours. */
    val downloading: Boolean = false,
) {
    val selectedCategory: VodCategory? get() = categories.firstOrNull { it.key == selected }
}

/** Code PIN demandé avant d'ouvrir une catégorie verrouillée. */
@Immutable
data class PinRequest(val create: Boolean, val key: CategoryKey)

/** Nombre d'entrées de « Continuer » lues (une par film, une par série). */
const val CONTINUE_LIMIT = 100

/** Lecture correspondant à une section : film, ou épisode d'une série. */
fun playbackKindOf(section: Section): PlaybackKind =
    if (section == Section.SERIES) PlaybackKind.EPISODE else PlaybackKind.MOVIE

/** Avancement à afficher ; aucun si la durée est inconnue. */
fun progressFraction(progress: Progress?): Float? =
    progress?.takeIf { it.durationMs > 0 }?.let { Formats.fraction(it.positionMs, it.durationMs) }

/**
 * Affiche d'une entrée du catalogue : barre si la lecture est commencée,
 * coche si vue (les deux pendant un revisionnage). [fallback] : position de
 * « Continuer », si le catalogue n'en connaît pas.
 */
fun posterOf(item: CatalogItem, fallback: Progress? = null): PosterUi = PosterUi(
    id = item.id,
    title = item.name,
    image = item.image,
    progress = progressFraction(item.progress ?: fallback),
    watched = item.watched,
)

/**
 * Catégorie ouverte. Le choix de l'utilisateur s'il est encore possible,
 * sinon la catégorie déjà ouverte, sinon celle par défaut : « Continuer »
 * s'il a des entrées, sinon la première catégorie du fournisseur ouvrable.
 * Une catégorie masquée, disparue ou verrouillée n'est pas ouvrable.
 */
fun resolveSelection(user: CategoryKey?, previous: CategoryKey?, data: VodData): CategoryKey? {
    fun openable(key: CategoryKey?): Boolean = when (key) {
        null -> false
        CategoryKey.Continue, CategoryKey.Favorites -> true
        is CategoryKey.Provider -> data.categories.any { it.key == key && !it.locked }
    }
    return when {
        openable(user) -> user
        openable(previous) -> previous
        data.continueItems.isNotEmpty() -> CategoryKey.Continue
        else -> data.categories.firstOrNull { !it.locked }?.key
            // Rien d'autre à ouvrir (toutes verrouillées) : « Continuer » et son explication.
            ?: if (data.categories.isNotEmpty()) CategoryKey.Continue else null
    }
}

/** Colonne de gauche : « Continuer », « Favoris », puis le fournisseur. */
fun categoriesOf(data: VodData): List<VodCategory> =
    listOf(
        VodCategory(CategoryKey.Continue, name = null, count = data.continueItems.size.toLong()),
        VodCategory(CategoryKey.Favorites, name = null, count = data.favoritesCount),
    ) + data.categories

/** Colonne affichée : « Continuer » et « Favoris » vides sont masqués, sauf s'ils sont ouverts. */
fun shownCategories(categories: List<VodCategory>, selected: CategoryKey?): List<VodCategory> =
    categories.filter { it.key is CategoryKey.Provider || (it.count ?: 0L) > 0L || it.key == selected }

/**
 * Positions (dans la liste complète) des titres déjà présents plus haut
 * parmi les entrées chargées [ids], qui commencent à [offset].
 */
fun repeatedPositions(offset: Int, ids: List<Long>): Set<Int> {
    val seen = HashSet<Long>(ids.size * 2)
    val repeated = HashSet<Int>()
    ids.forEachIndexed { index, id -> if (!seen.add(id)) repeated += offset + index }
    return repeated
}

/**
 * Clé d'une case de la grille paginée : l'identifiant du titre (stable, pour
 * le focus et la position), sauf pour une place pas encore lue ou un titre
 * répété, qui prennent une clé de position.
 */
fun posterKey(index: Int, id: Long?, repeated: Set<Int>): Any = when {
    id == null -> "placeholder:$index"
    index in repeated -> "repeated:$index"
    else -> id
}

package io.github.shweepsi.heystream.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.BelowTopBar
import io.github.shweepsi.heystream.ui.components.CategoryRow
import io.github.shweepsi.heystream.ui.components.HsTopBar
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.components.MessageState
import io.github.shweepsi.heystream.ui.components.PanelLabel
import io.github.shweepsi.heystream.ui.components.ParentalPinDialog
import io.github.shweepsi.heystream.ui.components.PinPurpose
import io.github.shweepsi.heystream.ui.components.PosterCard
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.toUiText
import java.text.NumberFormat
import kotlinx.coroutines.flow.first

/** Films ou séries : catégories à gauche, grille d'affiches à droite. */
@Composable
fun VodScreen(section: Section, onOpenItem: (Long) -> Unit, onBack: () -> Unit) {
    val session = appGraph().session
    val viewModel = viewModel { VodViewModel(session, section) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    // « Continuer » est une simple liste ; les autres catégories sont paginées.
    val pagingKey = state.selected?.takeIf { it != CategoryKey.Continue }
    val pagedFlow = remember(pagingKey) { pagingKey?.let(viewModel::pagedItems) }
    val pagedItems = pagedFlow?.collectAsLazyPagingItems()
    // Affiche ouverte en dernier : le focus y revient au retour de la fiche.
    var lastOpened by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler(onBack = onBack)
    VodContent(
        state = state,
        pagedItems = pagedItems,
        onCategoryClick = viewModel::onCategoryClick,
        onOpenItem = { id ->
            lastOpened = id
            onOpenItem(id)
        },
        onRetry = viewModel::retry,
        onRefreshCatalog = viewModel::refreshCatalog,
        onBack = onBack,
        focusPosterId = lastOpened,
    )
    pin?.let { request ->
        ParentalPinDialog(
            purpose = if (request.create) PinPurpose.Create else PinPurpose.Unlock,
            onSuccess = viewModel::onPinSuccess,
            onDismiss = viewModel::onPinDismiss,
        )
    }
}

/** Images laissées à un élément pour être composé avant de lui donner le focus (1 s environ). */
private const val FOCUS_FRAMES = 60

/**
 * Écran sans logique (captures d'écran). [pagedItems] : grille de la
 * catégorie ouverte, sauf « Continuer » ([VodUiState.continueItems]).
 * [focusPosterId] : affiche qui reprend le focus (retour d'une fiche) ; à
 * défaut, la catégorie ouverte le prend.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VodContent(
    state: VodUiState,
    pagedItems: LazyPagingItems<PosterUi>?,
    onCategoryClick: (VodCategory) -> Unit,
    onOpenItem: (Long) -> Unit,
    onRetry: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onBack: () -> Unit,
    focusPosterId: Long? = null,
) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    val title = stringResource(if (state.section == Section.SERIES) R.string.vod_title_series else R.string.vod_title_movies)
    val categoryRequester = remember { FocusRequester() }
    val restore = remember { FocusRestore(focusPosterId.takeIf { isTv }) }
    val categoriesState = rememberLazyListState()
    val latestState by rememberUpdatedState(state)

    // Télécommande : l'affiche ouverte en dernier, sinon la catégorie ouverte.
    LaunchedEffect(Unit) {
        if (!isTv) return@LaunchedEffect
        snapshotFlow { restore.settled }.first { it }
        if (restore.restored) return@LaunchedEffect
        snapshotFlow { latestState.categories.isNotEmpty() }.first { it }
        val opening = latestState
        // Catalogue vide (pas en téléchargement) : « Réessayer » prend le focus.
        if (opening.selected == null && !opening.downloading) return@LaunchedEffect
        // La catégorie ouverte peut être hors de la liste affichée (liste défilée) :
        // elle n'est pas composée, et ne pourrait pas prendre le focus.
        val layout = snapshotFlow { categoriesState.layoutInfo }.first { it.totalItemsCount > 0 }
        val index = opening.categories.indexOfFirst { it.key == opening.selected }.coerceAtLeast(0)
        if (layout.visibleItemsInfo.none { it.index == index }) categoriesState.scrollToItem(index)
        categoryRequester.requestWhenAttached()
    }

    val grid = @Composable { modifier: Modifier, edge: Dp, bottom: Dp ->
        GridPane(
            state = state,
            pagedItems = pagedItems,
            restore = restore,
            onOpenItem = onOpenItem,
            onRetry = onRetry,
            onRefreshCatalog = onRefreshCatalog,
            edge = edge,
            bottom = bottom,
            modifier = modifier,
        )
    }
    val categories = @Composable { modifier: Modifier, padding: PaddingValues ->
        CategoriesPanel(
            categories = state.categories,
            selected = state.selected,
            onClick = onCategoryClick,
            requester = categoryRequester,
            listState = categoriesState,
            contentPadding = padding,
            modifier = modifier,
        )
    }

    if (isTv) {
        // Comme sur Google TV : l'élément focalisé se place au tiers haut de la liste.
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvBringIntoViewSpec) {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(Hs.colors.background)
                    .padding(start = dimens.screenHorizontal, end = dimens.screenHorizontal - GRID_EDGE, top = dimens.screenVertical),
            ) {
                Column(
                    Modifier
                        .width(dimens.categoriesPanelWidth)
                        .fillMaxHeight(),
                ) {
                    PanelLabel(title)
                    categories(Modifier.weight(1f), PaddingValues(bottom = dimens.screenVertical))
                }
                Spacer(Modifier.width(dimens.gapLarge - GRID_EDGE))
                grid(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    GRID_EDGE,
                    dimens.screenVertical,
                )
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(Hs.colors.background),
        ) {
            HsTopBar(title = title, onBack = onBack)
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(BelowTopBar),
            ) {
                categories(
                    Modifier
                        .width(dimens.categoriesPanelWidth)
                        .fillMaxHeight(),
                    PaddingValues(horizontal = 12.dp, vertical = dimens.screenVertical),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Hs.colors.separator),
                )
                grid(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = dimens.screenVertical),
                    dimens.screenHorizontal,
                    dimens.screenVertical,
                )
            }
        }
    }
}

/**
 * Marge autour de la grille sur TV : l'affiche focalisée (×1,06) et son
 * contour ne sont pas rognés par les bords de la liste.
 */
private val GRID_EDGE = 8.dp

/** Part de la hauteur de la liste au-dessus de l'élément focalisé (télécommande). */
private const val PIVOT_FRACTION = 0.3f

/**
 * Défilement à la télécommande, comme sur Google TV : l'élément focalisé
 * s'aligne au tiers haut de la liste, sans jamais en déborder. Il n'est pas
 * rogné au focus, et la rangée suivante reste visible.
 */
@OptIn(ExperimentalFoundationApi::class)
private val TvBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = containerSize * PIVOT_FRACTION
        val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }
}

/** Demande le focus dès que l'élément est composé (quelques images au plus). */
private suspend fun FocusRequester.requestWhenAttached(frames: Int = FOCUS_FRAMES): Boolean {
    repeat(frames) {
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return true
        withFrameNanos { }
    }
    return false
}

/**
 * Retour d'une fiche, à la télécommande : l'affiche ouverte reprend le focus
 * si elle est affichée ; sinon (titre vu sorti de « Continuer », catégorie
 * vide…), la catégorie ouverte le prend.
 */
@Stable
private class FocusRestore(posterId: Long?) {
    /** Affiche qui doit reprendre le focus, tant que ce n'est pas réglé. */
    var posterId by mutableStateOf(posterId)
        private set

    /** Réglé : l'affiche a le focus, ou ne peut pas l'avoir. */
    var settled by mutableStateOf(posterId == null)
        private set

    var restored = false
        private set

    fun done(focused: Boolean) {
        if (settled) return
        restored = focused
        posterId = null
        settled = true
    }
}

/** Rien d'affiché qui puisse reprendre le focus (message, liste vide). */
@Composable
private fun FocusRestore.GiveUp() {
    if (posterId != null) LaunchedEffect(Unit) { done(focused = false) }
}

@Composable
private fun CategoriesPanel(
    categories: List<VodCategory>,
    selected: CategoryKey?,
    onClick: (VodCategory) -> Unit,
    requester: FocusRequester,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (Hs.isTv) 3.dp else 2.dp),
        // Retour depuis la grille : la dernière catégorie focalisée, sinon
        // la catégorie ouverte (pas la plus proche à l'écran).
        modifier = modifier.focusRestorer(requester),
    ) {
        itemsIndexed(categories, key = { _, category -> category.key.listKey }, contentType = { _, _ -> "category" }) { index, category ->
            val isSelected = category.key == selected
            // Focus d'ouverture : la catégorie ouverte, sinon la première.
            val focusTarget = isSelected || (selected == null && index == 0)
            CategoryRow(
                name = category.label(),
                onClick = { onClick(category) },
                count = category.count,
                selected = isSelected,
                locked = category.locked,
                modifier = if (focusTarget) Modifier.focusRequester(requester) else Modifier,
            )
        }
    }
}

@Composable
private fun VodCategory.label(): String = when (key) {
    CategoryKey.Continue -> stringResource(R.string.continue_watching)
    CategoryKey.Favorites -> stringResource(R.string.favorites)
    is CategoryKey.Provider -> name.orEmpty()
}

@Composable
private fun GridPane(
    state: VodUiState,
    pagedItems: LazyPagingItems<PosterUi>?,
    restore: FocusRestore,
    onOpenItem: (Long) -> Unit,
    onRetry: () -> Unit,
    onRefreshCatalog: () -> Unit,
    edge: Dp,
    bottom: Dp,
    modifier: Modifier,
) {
    val selected = state.selectedCategory
    // Sur TV, [edge] laisse la place de l'agrandissement au focus, en haut aussi.
    val gridPadding = PaddingValues(start = edge, end = edge, top = if (Hs.isTv) edge else 0.dp, bottom = bottom)
    Column(modifier) {
        when {
            state.loading -> LoadingState()
            state.error != null -> {
                restore.GiveUp()
                // Aucune catégorie à focaliser : « Réessayer » prend le focus.
                RetryMessage(title = state.error.resolve(), message = null, icon = Lucide.CircleAlert, onRetry = onRetry)
            }
            selected == null -> {
                restore.GiveUp()
                CatalogEmpty(state.downloading, onRefreshCatalog)
            }
            else -> {
                val count = if (selected.key == CategoryKey.Continue) state.continueItems.size.toLong() else selected.count
                GridHeader(
                    title = selected.label(),
                    count = count,
                    section = state.section,
                    modifier = Modifier.padding(horizontal = edge),
                )
                // Une grille par catégorie : position et focus propres à chacune.
                key(selected.key.listKey) {
                    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
                    val body = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                    val poster = @Composable { item: PosterUi, index: Int ->
                        PosterItem(item, index, restore, onOpenItem)
                    }
                    if (selected.key == CategoryKey.Continue) {
                        if (state.continueItems.isEmpty()) {
                            restore.GiveUp()
                            EmptyMessage(selected.key, state.section, body)
                        } else {
                            PosterGrid(gridState, gridPadding, restore, body) {
                                itemsIndexedList(state.continueItems, poster)
                            }
                        }
                    } else {
                        PagedBody(pagedItems, selected.key, state, gridState, gridPadding, restore, body, poster)
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.itemsIndexedList(items: List<PosterUi>, poster: @Composable (PosterUi, Int) -> Unit) {
    items(items.size, key = { items[it].id }, contentType = { POSTER }) { index -> poster(items[index], index) }
}

private const val POSTER = "poster"

@Composable
private fun PagedBody(
    items: LazyPagingItems<PosterUi>?,
    key: CategoryKey,
    state: VodUiState,
    gridState: LazyGridState,
    gridPadding: PaddingValues,
    restore: FocusRestore,
    modifier: Modifier,
    poster: @Composable (PosterUi, Int) -> Unit,
) {
    val refresh = items?.loadState?.refresh
    when {
        items == null -> LoadingState(modifier)
        items.itemCount > 0 -> {
            val keys = rememberPosterKeys(items)
            PosterGrid(gridState, gridPadding, restore, modifier) {
                items(
                    count = items.itemCount,
                    key = keys,
                    contentType = items.itemContentType { POSTER },
                ) { index ->
                    val item = items[index]
                    if (item == null) PosterPlaceholder() else poster(item, index)
                }
            }
        }
        refresh is LoadState.Error -> {
            restore.GiveUp()
            MessageState(
                title = refresh.error.toUiText().resolve(),
                icon = Lucide.CircleAlert,
                action = stringResource(R.string.retry),
                onAction = items::retry,
                modifier = modifier,
            )
        }
        // Fin de liste atteinte sans rien : la catégorie est vide.
        refresh is LoadState.NotLoading && items.loadState.append.endOfPaginationReached -> {
            restore.GiveUp()
            if (state.downloading && key is CategoryKey.Provider) {
                LoadingState(modifier, text = stringResource(R.string.catalog_downloading))
            } else {
                EmptyMessage(key, state.section, modifier)
            }
        }
        else -> LoadingState(modifier)
    }
}

/**
 * Clés de la grille paginée, lues dans le même instantané que les titres
 * en double. Un rafraîchissement du catalogue pendant le défilement peut
 * décaler les pages déjà lues : un titre apparaît alors deux fois jusqu'à
 * la relecture (catalogVersion), et deux clés égales feraient planter la
 * grille.
 */
@Composable
private fun rememberPosterKeys(items: LazyPagingItems<PosterUi>): (Int) -> Any {
    val snapshot = items.itemSnapshotList
    return remember(snapshot) {
        val repeated = repeatedPositions(snapshot.placeholdersBefore, snapshot.items.map(PosterUi::id))
        val keys: (Int) -> Any = { index -> posterKey(index, snapshot[index]?.id, repeated) }
        keys
    }
}

@Composable
private fun PosterGrid(
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    restore: FocusRestore,
    modifier: Modifier,
    content: LazyGridScope.() -> Unit,
) {
    val dimens = Hs.dimens
    // Affiche à refocaliser hors de l'écran (liste changée entre-temps) : abandon.
    if (restore.posterId != null) {
        LaunchedEffect(gridState) {
            val layout = snapshotFlow { gridState.layoutInfo }.first { it.totalItemsCount > 0 }
            if (layout.visibleItemsInfo.none { it.key == restore.posterId }) restore.done(focused = false)
        }
    }
    LazyVerticalGrid(
        columns = if (dimens.isTv) GridCells.Fixed(dimens.posterColumns) else GridCells.Adaptive(dimens.posterMinWidth),
        state = gridState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(dimens.posterGapHorizontal),
        verticalArrangement = Arrangement.spacedBy(dimens.posterGapVertical),
        // Retour depuis les catégories : la dernière affiche focalisée.
        modifier = modifier.focusRestorer(),
        content = content,
    )
}

@Composable
private fun PosterItem(
    poster: PosterUi,
    index: Int,
    restore: FocusRestore,
    onOpenItem: (Long) -> Unit,
) {
    val dimens = Hs.dimens
    var modifier: Modifier = Modifier
    if (poster.id == restore.posterId) {
        val requester = remember { FocusRequester() }
        modifier = modifier.focusRequester(requester)
        LaunchedEffect(Unit) { restore.done(focused = requester.requestWhenAttached(frames = 3)) }
    }
    // Haut depuis la première rangée : le focus reste dans la grille.
    if (dimens.isTv && index < dimens.posterColumns) modifier = modifier.focusProperties { up = FocusRequester.Cancel }
    PosterCard(
        title = poster.title,
        imageUrl = poster.image,
        onClick = { onOpenItem(poster.id) },
        progress = poster.progress,
        watched = poster.watched,
        modifier = modifier,
    )
}

/** Affiche pas encore lue : même place, rien d'activable. */
@Composable
private fun PosterPlaceholder() {
    val dimens = Hs.dimens
    Column(verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(dimens.radius))
                .background(Hs.colors.surface2),
        )
        Text(" ", style = if (dimens.isTv) Hs.type.small else Hs.type.body, maxLines = 1)
    }
}

/** Nom de la catégorie et nombre de titres (« 214 films »). */
@Composable
private fun GridHeader(title: String, count: Long?, section: Section, modifier: Modifier) {
    val dimens = Hs.dimens
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (dimens.isTv) 15.dp - GRID_EDGE else dimens.gap),
        horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 9.dp else 12.dp),
    ) {
        Text(
            text = title,
            style = Hs.type.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .alignByBaseline(),
        )
        // Liste vide : son explication suffit, pas de « 0 film ».
        if (count != null && count > 0) {
            val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val formatted = NumberFormat.getIntegerInstance(currentLocale()).format(count)
            Text(
                text = pluralStringResource(
                    if (section == Section.SERIES) R.plurals.vod_count_series else R.plurals.vod_count_movies,
                    quantity,
                    formatted,
                ),
                style = Hs.type.body,
                color = Hs.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** « Continuer », « Favoris » ou catégorie vides : une courte explication. */
@Composable
private fun EmptyMessage(key: CategoryKey, section: Section, modifier: Modifier) {
    val series = section == Section.SERIES
    when (key) {
        CategoryKey.Continue -> MessageState(
            title = stringResource(if (series) R.string.vod_continue_empty_series_title else R.string.vod_continue_empty_movies_title),
            message = stringResource(if (series) R.string.vod_continue_empty_series else R.string.vod_continue_empty_movies),
            icon = Lucide.Clock,
            modifier = modifier,
        )
        CategoryKey.Favorites -> MessageState(
            title = stringResource(R.string.vod_favorites_empty_title),
            message = stringResource(if (series) R.string.vod_favorites_empty_series else R.string.vod_favorites_empty_movies),
            icon = Lucide.Heart,
            modifier = modifier,
        )
        is CategoryKey.Provider -> MessageState(
            title = stringResource(R.string.category_empty),
            icon = if (series) Lucide.MonitorPlay else Lucide.Film,
            modifier = modifier,
        )
    }
}

/**
 * Aucune catégorie : catalogue en cours de téléchargement, ou pas encore
 * téléchargé (« Réessayer » prend alors le focus).
 */
@Composable
private fun CatalogEmpty(downloading: Boolean, onRefreshCatalog: () -> Unit) {
    if (downloading) {
        LoadingState(text = stringResource(R.string.catalog_downloading))
    } else {
        RetryMessage(
            title = stringResource(R.string.catalog_empty),
            message = stringResource(R.string.catalog_empty_message),
            icon = Lucide.WifiOff,
            onRetry = onRefreshCatalog,
        )
    }
}

/** Message avec « Réessayer », qui prend le focus à la télécommande. */
@Composable
private fun RetryMessage(title: String, message: String?, icon: ImageVector, onRetry: () -> Unit) {
    val retry = remember { FocusRequester() }
    if (Hs.isTv) LaunchedEffect(Unit) { retry.requestWhenAttached() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(retry),
    ) {
        MessageState(
            title = title,
            message = message,
            icon = icon,
            action = stringResource(R.string.retry),
            onAction = onRetry,
        )
    }
}

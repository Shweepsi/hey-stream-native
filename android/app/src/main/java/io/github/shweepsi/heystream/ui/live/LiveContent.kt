package io.github.shweepsi.heystream.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.components.CategoryRow
import io.github.shweepsi.heystream.ui.components.ChannelLogo
import io.github.shweepsi.heystream.ui.components.Clock
import io.github.shweepsi.heystream.ui.components.FocusScale
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.HsTopBar
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.components.LocalSurfaceFocused
import io.github.shweepsi.heystream.ui.components.MessageState
import io.github.shweepsi.heystream.ui.components.PanelLabel
import io.github.shweepsi.heystream.ui.components.ProgressRail
import io.github.shweepsi.heystream.ui.components.marqueeWhen
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Direct, sans état : trois panneaux (catégories | chaînes | aperçu) et le
 * lecteur. [video] est appelé **une seule fois, toujours au même endroit** :
 * aperçu et plein écran ne changent que sa taille et sa place (même lecteur,
 * aucun fondu entre deux lecteurs).
 *
 * [nowSecs] : heure courante (secondes Unix), pour la progression du programme.
 */
@Composable
fun LiveContent(
    state: LiveUiState,
    channels: LazyPagingItems<CatalogItem>,
    nowSecs: Long,
    actions: LiveActions,
    onBack: () -> Unit,
    video: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    val fullscreen by rememberUpdatedState(state.fullscreen)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Hs.colors.background)
            // Plein écran à la télécommande : pas de zapping ; OK, haut et bas montrent le bandeau.
            .onPreviewKeyEvent { event -> fullscreen && fullscreenKey(event, actions) },
    ) {
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val start = insets.calculateStartPadding(direction)
        val end = insets.calculateEndPadding(direction)
        val top = insets.calculateTopPadding()
        val bottom = insets.calculateBottomPadding()
        val layout = remember(maxWidth, maxHeight, start, end, top, bottom, isTv, dimens) {
            liveLayout(
                isTv = isTv,
                width = maxWidth - start - end,
                height = maxHeight - top - bottom,
                categories = dimens.categoriesPanelWidth,
                channels = dimens.channelsPanelWidth,
                // Barre du haut et son séparateur.
                topBar = dimens.topBarHeight + 1.dp,
            )
        }
        val categories = state.categories
        val showPanels = categories is CategoriesUi.Ready && !categories.catalogEmpty

        // Télécommande : « Réessayer » d'un écran d'erreur ou de catalogue vide a le focus.
        val rootAction = remember { FocusRequester() }
        val actionShown = when {
            categories is CategoriesUi.Failed -> 1
            categories is CategoriesUi.Ready && !showPanels && !state.downloading -> 2
            else -> 0
        }
        LaunchedEffect(actionShown) {
            if (!isTv || actionShown == 0) return@LaunchedEffect
            withFrameNanos { }
            runCatching { rootAction.requestFocus() }
        }

        Column(Modifier.fillMaxSize()) {
            // Au doigt, le fond de la barre va jusqu'aux bords (barre d'état, encoche).
            if (!isTv) {
                HsTopBar(
                    title = stringResource(R.string.live_title),
                    onBack = onBack,
                    actions = { Clock(showDate = false) },
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = start, end = end, top = if (isTv) top else 0.dp, bottom = bottom),
            ) {
                when {
                    categories is CategoriesUi.Loading -> LoadingState()
                    categories is CategoriesUi.Failed -> MessageState(
                        title = categories.error.resolve(),
                        icon = Lucide.CircleAlert,
                        action = stringResource(R.string.retry),
                        onAction = actions::onReloadCategories,
                        modifier = Modifier.focusRequester(rootAction),
                    )
                    !showPanels && state.downloading -> MessageState(
                        title = stringResource(R.string.catalog_downloading),
                        icon = Lucide.RefreshCw,
                    )
                    !showPanels -> MessageState(
                        title = stringResource(R.string.catalog_empty),
                        message = stringResource(R.string.catalog_empty_message),
                        action = stringResource(R.string.retry),
                        onAction = actions::onRefreshCatalog,
                        modifier = Modifier.focusRequester(rootAction),
                    )
                    else -> LivePanels(
                        state = state,
                        categories = categories.categories,
                        channels = channels,
                        layout = layout,
                        nowSecs = nowSecs,
                        actions = actions,
                    )
                }
            }
        }

        // Le lecteur, toujours composé ici : seule sa place change.
        val videoModifier = when {
            state.fullscreen -> Modifier.fillMaxSize()
            showPanels -> Modifier
                .offset(start + layout.videoX, top + layout.videoY)
                .size(layout.videoWidth, layout.videoHeight)
            else -> Modifier.size(0.dp)
        }
        LiveVideo(
            state = state,
            nowSecs = nowSecs,
            actions = actions,
            video = video,
            modifier = videoModifier,
        )

        val message = state.message
        if (message != null && (layout.compact || !showPanels)) {
            LiveToast(
                text = message.resolve(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottom + if (isTv) 24.dp else 20.dp),
            )
        }
    }
}

/** Touches du plein écran ; `true` si elle est consommée. */
private fun fullscreenKey(event: KeyEvent, actions: LiveActions): Boolean = when (event.key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
        if (event.type == KeyEventType.KeyUp) actions.onFullscreenKey(ok = true)
        true
    }
    Key.DirectionUp, Key.DirectionDown -> {
        if (event.type == KeyEventType.KeyDown) actions.onFullscreenKey(ok = false)
        true
    }
    // Pas de zapping en plein écran (spec). Menu et Espace iraient à la ligne qui garde le
    // focus derrière la vidéo (favori ou lecture d'une autre chaîne, sans rien montrer).
    Key.DirectionLeft, Key.DirectionRight, Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown, Key.Tab,
    Key.Menu, Key.Spacebar, Key.MoveHome, Key.MoveEnd,
    -> true
    else -> false
}

@Composable
private fun LivePanels(
    state: LiveUiState,
    categories: List<LiveCategory>,
    channels: LazyPagingItems<CatalogItem>,
    layout: LiveLayout,
    nowSecs: Long,
    actions: LiveActions,
) {
    val isTv = Hs.isTv
    val firstChannel = remember { FocusRequester() }
    val previewChannel = remember { FocusRequester() }
    val selectedCategory = remember { FocusRequester() }
    // Chaque catégorie s'ouvre en haut de sa liste.
    val channelList = remember(state.selected) { LazyListState() }

    // Focus de départ (télécommande) : la chaîne déjà en aperçu (venue de la recherche,
    // en plein écran : Retour revient sur elle), sinon la première chaîne, sinon la
    // catégorie ouverte. Au doigt, la chaîne en aperçu est seulement montrée.
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    val hasChannels = channels.itemCount > 0 && channels.peek(0) != null
    val settled = channels.loadState.refresh is LoadState.NotLoading && state.selected != null
    val previewId by rememberUpdatedState(state.preview?.id)
    LaunchedEffect(hasChannels, settled, initialFocusDone) {
        if (initialFocusDone || !(hasChannels || settled)) return@LaunchedEffect
        // Pages déjà chargées seulement (aucune requête) : sinon, la première chaîne.
        val previewIndex = previewId?.let { id -> channels.itemSnapshotList.indexOfFirst { it?.id == id } } ?: -1
        if (previewIndex > 0) channelList.scrollToItem((previewIndex - 2).coerceAtLeast(0))
        if (!isTv) {
            initialFocusDone = true
            return@LaunchedEffect
        }
        val target = when {
            previewIndex >= 0 -> previewChannel
            hasChannels -> firstChannel
            else -> selectedCategory
        }
        // Les lignes d'une liste paresseuse sont composées à la mise en page : attendre une image.
        repeat(3) {
            withFrameNanos { }
            if (runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                initialFocusDone = true
                return@LaunchedEffect
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = layout.marginHorizontal, vertical = layout.marginVertical),
        horizontalArrangement = Arrangement.spacedBy(layout.gap),
    ) {
        CategoriesPanel(
            categories = shownCategories(categories, state.selected),
            selected = state.selected,
            selectedFocus = selectedCategory,
            compact = layout.compact,
            onSelect = actions::onSelectCategory,
            modifier = Modifier
                .width(layout.categoriesWidth)
                .fillMaxHeight(),
        )
        ChannelsPanel(
            state = state,
            channels = channels,
            listState = channelList,
            firstFocus = firstChannel,
            previewFocus = previewChannel,
            compact = layout.compact,
            actions = actions,
            modifier = Modifier
                .width(layout.channelsWidth)
                .fillMaxHeight(),
        )
        PreviewPanel(
            preview = state.preview,
            message = state.message?.resolve(),
            layout = layout,
            nowSecs = nowSecs,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

/** Trait vertical en bout de colonne (tablette, téléphone). */
private fun Modifier.endLine(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Ltr) size.width - stroke / 2 else stroke / 2
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
}

@Composable
private fun CategoriesPanel(
    categories: List<LiveCategory>,
    selected: CategoryKey?,
    selectedFocus: FocusRequester,
    compact: Boolean,
    onSelect: (LiveCategory) -> Unit,
    modifier: Modifier,
) {
    val isTv = Hs.isTv
    val listState = rememberLazyListState()
    val selectedIndex = categories.indexOfFirst { it.key == selected }
    // Catégorie ouverte hors de vue (chaîne venue de la recherche) : la montrer.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        withFrameNanos { }
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == selectedIndex }) listState.scrollToItem(selectedIndex)
    }
    Column(
        if (isTv) {
            modifier
        } else {
            modifier
                .endLine(Hs.colors.separator)
                .padding(horizontal = if (compact) 8.dp else 12.dp)
        },
    ) {
        if (isTv) PanelLabel(stringResource(R.string.live_categories))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer(),
            // Marges dans la liste : les lignes défilent jusqu'au bord, sans être coupées avant.
            contentPadding = PaddingValues(vertical = if (isTv) 0.dp else if (compact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isTv) 3.dp else 2.dp),
        ) {
            items(categories, key = { it.key.listKey }, contentType = { "category" }) { category ->
                val isSelected = category.key == selected
                CategoryRow(
                    name = category.name ?: stringResource(
                        if (category.key == CategoryKey.Favorites) R.string.favorites else R.string.recents,
                    ),
                    onClick = { onSelect(category) },
                    count = category.count,
                    selected = isSelected,
                    locked = category.locked,
                    icon = when (category.key) {
                        CategoryKey.Favorites -> Lucide.Heart
                        CategoryKey.Recents -> Lucide.Clock
                        is CategoryKey.Provider -> null
                    },
                    modifier = if (isSelected) Modifier.focusRequester(selectedFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChannelsPanel(
    state: LiveUiState,
    channels: LazyPagingItems<CatalogItem>,
    listState: LazyListState,
    firstFocus: FocusRequester,
    previewFocus: FocusRequester,
    compact: Boolean,
    actions: LiveActions,
    modifier: Modifier,
) {
    val isTv = Hs.isTv
    val colors = Hs.colors
    val vertical = if (isTv) 9.dp else if (compact) 8.dp else 16.dp
    val panel = if (isTv) {
        modifier
            .clip(RoundedCornerShape(Hs.dimens.radius))
            .background(colors.surface)
            .padding(horizontal = 9.dp)
    } else {
        modifier
            .background(colors.surface)
            .endLine(colors.separator)
            .padding(horizontal = if (compact) 8.dp else 12.dp)
    }
    Box(panel) {
        val refresh = channels.loadState.refresh
        val selected = state.selected
        when {
            channels.itemCount > 0 -> ChannelList(state, channels, listState, vertical, firstFocus, previewFocus, compact, actions)
            refresh is LoadState.Error -> MessageState(
                title = refresh.error.toUiText().resolve(),
                icon = Lucide.CircleAlert,
                action = stringResource(R.string.retry),
                onAction = channels::retry,
            )
            refresh !is LoadState.NotLoading || selected == null -> Unit
            state.downloading -> MessageState(title = stringResource(R.string.catalog_downloading))
            selected == CategoryKey.Favorites -> MessageState(
                title = stringResource(R.string.live_favorites_empty),
                message = stringResource(if (isTv) R.string.live_favorites_empty_tv else R.string.live_favorites_empty_touch),
                icon = Lucide.Heart,
            )
            selected == CategoryKey.Recents -> MessageState(
                title = stringResource(R.string.live_recents_empty),
                message = stringResource(R.string.live_recents_empty_message),
                icon = Lucide.Clock,
            )
            else -> MessageState(title = stringResource(R.string.category_empty))
        }
    }
}

@Composable
private fun ChannelList(
    state: LiveUiState,
    channels: LazyPagingItems<CatalogItem>,
    listState: LazyListState,
    vertical: Dp,
    firstFocus: FocusRequester,
    previewFocus: FocusRequester,
    compact: Boolean,
    actions: LiveActions,
) {
    val isTv = Hs.isTv
    LaunchedEffect(listState, channels) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                emptyList()
            } else {
                visibleWindow(visible.first().index, visible.last().index, channels.itemCount)
                    .mapNotNull { channels.peek(it)?.id }
            }
        }
            .distinctUntilChanged()
            .collect { actions.onVisibleChannels(it) }
    }
    val previewId = state.preview?.id
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer(),
        contentPadding = PaddingValues(vertical = vertical),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 3.dp else 4.dp),
    ) {
        items(
            count = channels.itemCount,
            // Numéro de ligne dans la clé : jamais deux fois la même, même si le catalogue
            // change entre deux pages ; stable tant que la chaîne garde sa place.
            key = { index -> channels.peek(index)?.let { "${it.id}@$index" } ?: "@$index" },
            contentType = channels.itemContentType { "channel" },
        ) { index ->
            val channel = channels[index]
            if (channel == null) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(Hs.dimens.channelRowHeight),
                )
            } else {
                ChannelRow(
                    channel = channel,
                    nowTitle = state.nowTitles[channel.id],
                    favorite = channel.id in state.favoriteIds,
                    inPreview = channel.id == previewId,
                    compact = compact,
                    onClick = { actions.onChannelClick(channel) },
                    onToggleFavorite = { actions.onToggleFavorite(channel) },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (channel.id == previewId) Modifier.focusRequester(previewFocus) else Modifier),
                )
            }
        }
    }
}

/**
 * Ligne de chaîne : numéro (absent s'il n'est pas fourni), logo, nom, et le
 * programme en cours s'il est déjà en cache ; cœur si favorite. Appui long,
 * ou touche Menu, pour l'ajouter ou la retirer des favoris.
 */
@Composable
private fun ChannelRow(
    channel: CatalogItem,
    nowTitle: String?,
    favorite: Boolean,
    inPreview: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    val favoriteLabel = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add)
    val name = Formats.displayName(channel.name)
    HsSurface(
        onClick = onClick,
        onLongClick = onToggleFavorite,
        selected = inPreview,
        focusScale = FocusScale.NONE,
        onClickLabel = name,
        contentPadding = PaddingValues(horizontal = if (isTv) 9.dp else if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.channelRowHeight)
            .onKeyEvent { event ->
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) onToggleFavorite()
                    true
                } else {
                    false
                }
            }
            .semantics {
                customActions = listOf(CustomAccessibilityAction(favoriteLabel) { onToggleFavorite(); true })
            },
    ) {
        val content = LocalContentColor.current
        val secondary = Hs.colors.textSecondary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else if (compact) 8.dp else 12.dp),
        ) {
            ChannelLogo(channel.image, name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // Nom et programme complets : chaîne focalisée sur TV, en aperçu au doigt.
                val highlighted = if (isTv) LocalSurfaceFocused.current else inPreview
                Text(
                    text = name,
                    style = Hs.type.itemTitle,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.marqueeWhen(highlighted),
                )
                if (nowTitle != null) {
                    Text(
                        text = nowTitle,
                        style = Hs.type.small,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.marqueeWhen(highlighted),
                    )
                }
            }
            if (favorite) {
                Icon(
                    Lucide.Heart,
                    contentDescription = stringResource(R.string.favorites),
                    tint = secondary,
                    modifier = Modifier.size(dimens.icon * 0.9f),
                )
            }
        }
    }
}

/** Sous l'aperçu vidéo : programme en cours (et sa progression), puis le suivant. */
@Composable
private fun PreviewPanel(
    preview: LivePreview?,
    message: String?,
    layout: LiveLayout,
    nowSecs: Long,
    modifier: Modifier,
) {
    val isTv = Hs.isTv
    Column(modifier.padding(layout.previewPadding)) {
        // Place de l'aperçu : le lecteur est posé par-dessus (même lecteur en plein écran).
        Spacer(Modifier.height(layout.videoHeight))
        FitColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = if (isTv) 15.dp else if (layout.compact) 10.dp else 20.dp),
        ) {
            if (preview == null) {
                Text(stringResource(R.string.live_preview_hint), style = Hs.type.body, color = Hs.colors.textSecondary)
            } else {
                NowBlock(preview, nowSecs, layout.compact)
                preview.next?.let { NextRow(it, Modifier.padding(top = if (isTv) 12.dp else 16.dp)) }
            }
        }
        if (!layout.compact && message != null) {
            LiveToast(message, Modifier.padding(top = 8.dp))
        } else if (!layout.compact) {
            Text(
                text = stringResource(if (isTv) R.string.live_favorite_hint_tv else R.string.live_favorite_hint_touch),
                style = Hs.type.small,
                color = Hs.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NowBlock(preview: LivePreview, nowSecs: Long, compact: Boolean) {
    val isTv = Hs.isTv
    val locale = currentLocale()
    val titleStyle = when {
        isTv -> Hs.type.title.copy(fontSize = 18.sp)
        compact -> Hs.type.itemTitle
        else -> Hs.type.title
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTv) 5.dp else 6.dp)) {
        val now = preview.now
        if (now == null) {
            Text(preview.name, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            val range = stringResource(
                R.string.live_time_range,
                Formats.timeOfSeconds(now.start, locale),
                Formats.timeOfSeconds(now.stop, locale),
            )
            Text(
                text = stringResource(R.string.live_joined, range, preview.name),
                style = Hs.type.small.tabular(),
                color = Hs.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(now.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ProgressRail(
                fraction = Formats.fraction(nowSecs - now.start, now.stop - now.start),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun NextRow(next: LiveProgramme, modifier: Modifier = Modifier) {
    val isTv = Hs.isTv
    val separator = Hs.colors.separator
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawLine(separator, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .padding(vertical = if (isTv) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 16.dp),
    ) {
        Text(
            text = Formats.timeOfSeconds(next.start, currentLocale()),
            style = Hs.type.body.tabular(),
            color = Hs.colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.widthIn(min = if (isTv) 42.dp else 52.dp),
        )
        Text(next.title, style = Hs.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Colonne qui ne place ses enfants que tant qu'ils tiennent en entier
 * (téléphone : le programme suivant disparaît plutôt que d'être coupé).
 */
@Composable
private fun FitColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placed = ArrayList<Pair<Placeable, Int>>(measurables.size)
        var bottom = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            val top = bottom
            if (top + placeable.height > constraints.maxHeight) break
            placed += placeable to top
            bottom = top + placeable.height
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placed.maxOfOrNull { it.first.width } ?: 0
        layout(width, constraints.constrainHeight(bottom)) {
            placed.forEach { (placeable, top) -> placeable.placeRelative(0, top) }
        }
    }
}

/** Confirmation brève (favori), en bas de l'écran. */
@Composable
private fun LiveToast(text: String, modifier: Modifier = Modifier) {
    val colors = Hs.colors
    Text(
        text = text,
        style = Hs.type.label,
        color = colors.background,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(Hs.dimens.radius))
            .background(colors.text)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

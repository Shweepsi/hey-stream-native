package io.github.shweepsi.heystream.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.BelowTopBar
import io.github.shweepsi.heystream.ui.components.ChannelLogo
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.HsTopBar
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.components.MessageState
import io.github.shweepsi.heystream.ui.components.PosterCard
import io.github.shweepsi.heystream.ui.components.SafeSides
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.theme.tabular

/** Recherche par nom dans le direct, les films et les séries. */
@Composable
fun SearchScreen(onOpenChannel: (Long) -> Unit, onOpenMovie: (Long) -> Unit, onOpenSeries: (Long) -> Unit, onBack: () -> Unit) {
    val session = appGraph().session
    val viewModel = viewModel { SearchViewModel(session) }
    val results by viewModel.results.collectAsStateWithLifecycle()
    // Au retour d'une fiche, le focus revient sur le résultat ouvert.
    val restore = remember { viewModel.lastOpened }
    BackHandler(onBack = onBack)
    SearchContent(
        query = viewModel.query,
        results = results,
        onQueryChange = viewModel::onQueryChange,
        onOpen = { hit ->
            viewModel.onOpen(hit)
            when (hit.section) {
                Section.LIVE -> onOpenChannel(hit.id)
                Section.MOVIES -> onOpenMovie(hit.id)
                Section.SERIES -> onOpenSeries(hit.id)
            }
        },
        onBack = onBack,
        focusKey = restore,
    )
}

/**
 * Écran de recherche, sans logique. [focusKey] : résultat qui reçoit le
 * focus à l'ouverture (retour d'une fiche) ; sinon le champ, et sur TV le
 * clavier du système s'ouvre (à la première ouverture seulement).
 */
@Composable
fun SearchContent(
    query: String,
    results: SearchResults,
    onQueryChange: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onBack: () -> Unit,
    focusKey: String? = null,
) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    val fieldFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
    // Bas depuis le champ : la première rangée de résultats, sur son premier
    // résultat (ou celui qu'on y a quitté), pas sur celui qui se trouve sous
    // le milieu du champ.
    val firstFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (focusKey == null) {
            if (fieldFocus.requestFocusWhenReady() && isTv) keyboard?.show()
        } else if (!restoreFocus.requestFocusWhenReady()) {
            // Retour d'une fiche dont le résultat n'est plus là : le champ,
            // sans rouvrir le clavier par-dessus les résultats.
            fieldFocus.requestFocusWhenReady()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Hs.colors.background),
    ) {
        if (!isTv) HsTopBar(stringResource(R.string.search_title), onBack)
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            onSubmit = { keyboard?.hide() },
            results = firstFocus,
            modifier = Modifier
                .windowInsetsPadding(SafeSides)
                .padding(
                    start = dimens.screenHorizontal,
                    end = dimens.screenHorizontal,
                    top = if (isTv) dimens.screenVertical else dimens.gap,
                )
                .focusRequester(fieldFocus),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(BelowTopBar),
        ) {
            when (results) {
                SearchResults.Idle -> MessageState(
                    title = stringResource(R.string.search_idle_title),
                    message = stringResource(R.string.search_idle_message),
                    icon = Lucide.Search,
                )
                SearchResults.Downloading -> LoadingState(text = stringResource(R.string.catalog_downloading))
                is SearchResults.Failed -> MessageState(title = results.error.resolve(), icon = Lucide.CircleAlert, alert = true)
                is SearchResults.Found -> if (results.isEmpty) {
                    MessageState(
                        title = stringResource(R.string.search_no_results),
                        message = stringResource(R.string.search_no_results_message, results.term),
                        icon = Lucide.Search,
                    )
                } else {
                    ResultList(results, onOpen, focusKey, restoreFocus, firstFocus)
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    results: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dimens.radius)
    val label = stringResource(R.string.search_field_label)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Hs.type.body.copy(color = Hs.colors.text),
        cursorBrush = SolidColor(Hs.colors.text),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            // Bas : du champ vers les résultats (télécommande, clavier).
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                // Sans résultat composé, le requérant n'est rattaché à rien.
                if (!runCatching { results.requestFocus() }.getOrDefault(false)) {
                    focusManager.moveFocus(FocusDirection.Down)
                }
                // Touche consommée même sans résultat : le focus reste dans le
                // champ au lieu de partir chercher une vue hors de Compose.
                true
            }
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .heightIn(min = dimens.minTouch + 4.dp)
            .border(dimens.focusBorder, if (focused) HsColors.Focus else Hs.colors.surface2, shape)
            .background(Hs.colors.surface2, shape)
            .semantics { contentDescription = label },
        decorationBox = { field ->
            Row(
                Modifier.padding(horizontal = if (dimens.isTv) 12.dp else 14.dp, vertical = if (dimens.isTv) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 10.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Search, contentDescription = null, tint = Hs.colors.textSecondary, modifier = Modifier.size(dimens.icon))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.search_hint),
                            style = Hs.type.body,
                            color = Hs.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            }
        },
    )
}

/** Résultats groupés par section, chacune en rangée horizontale. */
@Composable
private fun ResultList(
    found: SearchResults.Found,
    onOpen: (SearchHit) -> Unit,
    focusKey: String?,
    restoreFocus: FocusRequester,
    firstFocus: FocusRequester,
) {
    val dimens = Hs.dimens
    val firstKey = (found.channels.firstOrNull() ?: found.movies.firstOrNull() ?: found.series.firstOrNull())?.key
    fun Modifier.requesters(hit: SearchHit): Modifier = this
        .then(if (hit.key == focusKey) Modifier.focusRequester(restoreFocus) else Modifier)
        .then(if (hit.key == firstKey) Modifier.focusRequester(firstFocus) else Modifier)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = dimens.gapLarge, bottom = dimens.screenVertical),
        verticalArrangement = Arrangement.spacedBy(dimens.gapLarge),
    ) {
        resultSection(Section.LIVE, R.string.search_section_live, found.channels) { hit, modifier ->
            ChannelResult(hit, onClick = { onOpen(hit) }, modifier = modifier.requesters(hit))
        }
        resultSection(Section.MOVIES, R.string.search_section_movies, found.movies) { hit, modifier ->
            PosterResult(hit, onClick = { onOpen(hit) }, modifier = modifier.requesters(hit))
        }
        resultSection(Section.SERIES, R.string.search_section_series, found.series) { hit, modifier ->
            PosterResult(hit, onClick = { onOpen(hit) }, modifier = modifier.requesters(hit))
        }
    }
}

/** Une section : titre, nombre de résultats, rangée défilante (absente si vide). */
private fun LazyListScope.resultSection(
    section: Section,
    title: Int,
    hits: List<SearchHit>,
    content: @Composable (SearchHit, Modifier) -> Unit,
) {
    if (hits.isEmpty()) return
    item(key = section.name, contentType = if (section == Section.LIVE) "channels" else "posters") {
        val dimens = Hs.dimens
        Column(verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 10.dp else 12.dp)) {
            SectionHeader(stringResource(title), hits.size)
            LazyRow(
                state = rememberLazyListState(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRestorer(),
                contentPadding = PaddingValues(horizontal = dimens.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(dimens.posterGapHorizontal),
            ) {
                items(hits, key = { it.key }, contentType = { it.section }) { hit ->
                    content(hit, Modifier)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val dimens = Hs.dimens
    Row(
        Modifier.padding(horizontal = dimens.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 10.dp else 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = Hs.type.title.copy(fontSize = if (dimens.isTv) 18.sp else 19.sp),
            maxLines = 1,
            modifier = Modifier
                .alignByBaseline()
                .semantics { heading() },
        )
        Text(
            text = if (count >= SEARCH_LIMIT) {
                stringResource(R.string.search_results_first, count)
            } else {
                pluralStringResource(R.plurals.search_results_count, count, count)
            },
            style = Hs.type.small.tabular(),
            color = Hs.colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** Chaîne : numéro du fournisseur, logo, nom. */
@Composable
private fun ChannelResult(hit: SearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    HsSurface(
        onClick = onClick,
        color = Hs.colors.surface,
        onClickLabel = hit.name,
        modifier = modifier
            .width(if (dimens.isTv) 220.dp else 260.dp)
            .height(dimens.channelRowHeight),
        contentPadding = PaddingValues(horizontal = if (dimens.isTv) 10.dp else 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(hit.image, hit.name)
            Text(
                text = hit.name,
                style = Hs.type.itemTitle,
                color = LocalContentColor.current,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Film ou série : affiche, progression, coche « vu ». */
@Composable
private fun PosterResult(hit: SearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PosterCard(
        title = hit.name,
        imageUrl = hit.image,
        onClick = onClick,
        progress = hit.progress,
        watched = hit.watched,
        fallbackIcon = if (hit.section == Section.SERIES) Lucide.MonitorPlay else Lucide.Film,
        modifier = modifier.width(Hs.dimens.posterMinWidth),
    )
}

/**
 * Demande le focus dès que l'élément est composé (les listes composent
 * leurs éléments à la mise en page, après le premier passage).
 */
private suspend fun FocusRequester.requestFocusWhenReady(): Boolean {
    repeat(FOCUS_ATTEMPTS) {
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return true
        withFrameNanos { }
    }
    return false
}

private const val FOCUS_ATTEMPTS = 5

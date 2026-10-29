package io.github.shweepsi.heystream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.components.BelowTopBar
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.CategoryRow
import io.github.shweepsi.heystream.ui.components.FocusScale
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.HsSurface
import io.github.shweepsi.heystream.ui.components.HsTopBar
import io.github.shweepsi.heystream.ui.components.LoadingState
import io.github.shweepsi.heystream.ui.components.Logo
import io.github.shweepsi.heystream.ui.components.MessageState
import io.github.shweepsi.heystream.ui.components.PanelLabel
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.currentLocale
import io.github.shweepsi.heystream.ui.home.Subscription
import io.github.shweepsi.heystream.ui.home.text
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import java.text.NumberFormat

/** Licences dont le texte est fourni dans `assets/licenses/`. */
enum class License(val asset: String, @StringRes val title: Int, @StringRes val licenseName: Int) {
    Inter("licenses/Inter-OFL.txt", R.string.settings_credit_inter, R.string.settings_credit_inter_license),
    Lucide("licenses/Lucide-ISC.txt", R.string.settings_credit_lucide, R.string.settings_credit_lucide_license),
}

/** Actions des réglages ; par défaut, rien (captures d'écran). */
@Stable
interface SettingsActions {
    fun select(section: SettingsSection) {}

    fun selectCategorySection(section: Section) {}

    fun toggleCategory(category: CategoryToggle) {}

    fun createPin() {}

    fun changePin() {}

    fun lockNow() {}

    fun language(choice: LanguageChoice) {}

    fun refreshCatalog() {}

    fun signOut() {}

    fun changeServer() {}

    fun showLicense(license: License) {}

    fun back() {}
}

@get:StringRes
val SettingsSection.title: Int
    get() = when (this) {
        SettingsSection.HiddenCategories -> R.string.settings_section_hidden
        SettingsSection.Parental -> R.string.settings_section_parental
        SettingsSection.Language -> R.string.settings_section_language
        SettingsSection.Catalog -> R.string.settings_section_catalog
        SettingsSection.Subscription -> R.string.settings_section_subscription
        SettingsSection.About -> R.string.settings_section_about
    }

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.HiddenCategories -> Lucide.EyeOff
        SettingsSection.Parental -> Lucide.ShieldCheck
        SettingsSection.Language -> Lucide.Languages
        SettingsSection.Catalog -> Lucide.RefreshCw
        SettingsSection.Subscription -> Lucide.User
        SettingsSection.About -> Lucide.Info
    }

@get:StringRes
private val Section.tab: Int
    get() = when (this) {
        Section.LIVE -> R.string.settings_tab_live
        Section.MOVIES -> R.string.settings_tab_movies
        Section.SERIES -> R.string.settings_tab_series
    }

/**
 * Réglages en deux panneaux : les rubriques à gauche, le contenu de la
 * rubrique choisie à droite. À la télécommande, le focus d'une rubrique
 * l'ouvre ; OK ou droite entre dans son contenu, Retour en ressort.
 */
@Composable
fun SettingsContent(state: SettingsState, language: LanguageChoice, appVersion: String, actions: SettingsActions) {
    val dimens = Hs.dimens
    val isTv = dimens.isTv
    val sectionFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    var contentFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sectionFocus.getValue(state.section).requestFocusWhenReady() }
    // Télécommande : Retour depuis le contenu revient à la rubrique.
    BackHandler(enabled = isTv && contentFocused) {
        runCatching { sectionFocus.getValue(state.section).requestFocus() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Hs.colors.background),
    ) {
        if (!isTv) HsTopBar(stringResource(R.string.settings_title), actions::back)
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(BelowTopBar)
                .padding(start = dimens.screenHorizontal, end = dimens.screenHorizontal, top = dimens.screenVertical),
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 30.dp else dimens.gapLarge),
        ) {
            SectionList(
                selected = state.section,
                focus = sectionFocus,
                onSelect = actions::select,
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { contentFocused = it.hasFocus },
            ) {
                when (state.section) {
                    SettingsSection.HiddenCategories -> HiddenCategoriesPane(state.categories, actions)
                    SettingsSection.Parental -> ParentalPane(state.parental, actions)
                    SettingsSection.Language -> LanguagePane(language, actions)
                    SettingsSection.Catalog -> CatalogPane(state.catalog, actions)
                    SettingsSection.Subscription -> SubscriptionPane(state.account, state.signingOut, actions)
                    SettingsSection.About -> AboutPane(appVersion, state.coreVersion, actions)
                }
            }
        }
    }
}

@Composable
private fun SectionList(
    selected: SettingsSection,
    focus: Map<SettingsSection, FocusRequester>,
    onSelect: (SettingsSection) -> Unit,
) {
    val dimens = Hs.dimens
    val focusManager = LocalFocusManager.current
    Column(
        Modifier
            .width(if (dimens.isTv) 210.dp else 240.dp)
            .fillMaxHeight()
            .focusRestorer(focus.getValue(selected))
            .verticalScroll(rememberScrollState())
            .padding(bottom = dimens.screenVertical),
        verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 3.dp else 4.dp),
    ) {
        if (dimens.isTv) PanelLabel(stringResource(R.string.settings_title))
        SettingsSection.entries.forEach { section ->
            CategoryRow(
                name = stringResource(section.title),
                icon = section.icon,
                selected = section == selected,
                onClick = {
                    onSelect(section)
                    if (dimens.isTv) focusManager.moveFocus(FocusDirection.Right)
                },
                modifier = Modifier
                    .focusRequester(focus.getValue(section))
                    .onFocusChanged { if (it.isFocused) onSelect(section) },
            )
        }
    }
}

/** Largeur maximale du contenu d'une rubrique (lignes lisibles sur tablette). */
private val PaneMaxWidth = 680.dp

/** Contenu d'une rubrique : titre, puis le reste, défilant si besoin. */
@Composable
private fun Pane(title: String, content: @Composable ColumnScope.() -> Unit) {
    val dimens = Hs.dimens
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            Modifier
                .widthIn(max = PaneMaxWidth)
                .fillMaxWidth()
                .padding(bottom = dimens.screenVertical),
            verticalArrangement = Arrangement.spacedBy(dimens.gap),
        ) {
            PaneTitle(title)
            content()
        }
    }
}

@Composable
private fun PaneTitle(title: String) {
    Text(
        text = title,
        style = Hs.type.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = if (Hs.isTv) 0.dp else 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Paragraph(text: String, modifier: Modifier = Modifier, secondary: Boolean = true) {
    Text(text, style = Hs.type.body, color = if (secondary) Hs.colors.textSecondary else Hs.colors.text, modifier = modifier)
}

// Catégories masquées -----------------------------------------------------------

/**
 * Une seule liste (titre, explication, onglets, catégories) : sur un petit
 * écran, l'en-tête défile avec les catégories au lieu de leur prendre la place.
 */
@Composable
private fun HiddenCategoriesPane(ui: CategoriesUi, actions: SettingsActions) {
    val dimens = Hs.dimens
    val items = ui.items
    val tabFocus = remember { Section.entries.associateWith { FocusRequester() } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier
                .widthIn(max = PaneMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                // Première entrée : l'onglet ouvert (pas le plus proche, qui en changerait).
                .focusRestorer(tabFocus.getValue(ui.section)),
            contentPadding = PaddingValues(bottom = dimens.screenVertical),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "title", contentType = "title") { PaneTitle(stringResource(R.string.settings_section_hidden)) }
            item(key = "intro", contentType = "text") {
                Paragraph(stringResource(R.string.settings_hidden_intro), Modifier.padding(top = dimens.gap - 2.dp))
            }
            item(key = "tabs", contentType = "tabs") {
                SectionTabs(ui.section, tabFocus, actions::selectCategorySection, Modifier.padding(vertical = dimens.gap - 2.dp))
            }
            if (ui.error != null) {
                item(key = "error", contentType = "text") {
                    Text(ui.error.resolve(), style = Hs.type.small, color = Hs.colors.text, modifier = Modifier.padding(bottom = dimens.gapSmall))
                }
            }
            when {
                items == null -> item(key = "loading", contentType = "message") { LoadingState(Modifier.padding(top = dimens.gapLarge)) }
                items.isEmpty() && ui.downloading -> item(key = "downloading", contentType = "message") {
                    LoadingState(Modifier.padding(top = dimens.gapLarge), text = stringResource(R.string.catalog_downloading))
                }
                items.isEmpty() && ui.error == null -> item(key = "empty", contentType = "message") {
                    MessageState(
                        title = stringResource(R.string.settings_categories_empty),
                        message = stringResource(R.string.catalog_empty_message),
                    )
                }
                else -> itemsIndexed(items, key = { _, it -> "${ui.section.name}:${it.id}" }, contentType = { _, _ -> "category" }) { index, category ->
                    CategoryToggleRow(
                        category = category,
                        onToggle = { actions.toggleCategory(category) },
                        // Haut depuis la première : l'onglet ouvert, pas le plus proche (qui changerait la liste).
                        modifier = if (index == 0) Modifier.focusProperties { up = tabFocus.getValue(ui.section) } else Modifier,
                    )
                }
            }
        }
    }
}

/**
 * Direct / Films / Séries : la section dont on règle les catégories. À la
 * télécommande, le focus d'un onglet l'ouvre ; en revenant de la liste, le
 * focus retrouve l'onglet ouvert.
 */
@Composable
private fun SectionTabs(
    selected: Section,
    focus: Map<Section, FocusRequester>,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = Hs.dimens
    val shape = RoundedCornerShape(dimens.radius)
    Row(
        modifier
            .focusRestorer(focus.getValue(selected))
            .clip(shape)
            .background(Hs.colors.surface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Section.entries.forEach { section ->
            val isSelected = section == selected
            HsSurface(
                onClick = { onSelect(section) },
                selected = isSelected,
                role = Role.Tab,
                contentColor = if (isSelected) Hs.colors.text else Hs.colors.textSecondary,
                contentPadding = PaddingValues(horizontal = if (dimens.isTv) 16.dp else 20.dp),
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = dimens.minTouch)
                    .focusRequester(focus.getValue(section))
                    .onFocusChanged { if (it.isFocused) onSelect(section) },
            ) {
                Text(
                    text = stringResource(section.tab),
                    style = Hs.type.label.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                    color = LocalContentColor.current,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Catégorie : nom, cadenas si verrouillée, interrupteur (allumé : affichée). */
@Composable
private fun CategoryToggleRow(category: CategoryToggle, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    val state = stringResource(if (category.hidden) R.string.settings_category_hidden else R.string.settings_category_shown)
    HsSurface(
        onClick = onToggle,
        focusScale = FocusScale.NONE,
        role = Role.Switch,
        contentPadding = PaddingValues(horizontal = if (dimens.isTv) 12.dp else 14.dp),
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.categoryRowHeight)
            .semantics {
                stateDescription = state
                toggleableState = ToggleableState(!category.hidden)
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = category.name,
                style = Hs.type.body,
                color = if (category.hidden) Hs.colors.textSecondary else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (category.locked) {
                Icon(
                    Lucide.Lock,
                    contentDescription = stringResource(R.string.category_locked),
                    tint = Hs.colors.textSecondary,
                    modifier = Modifier.size(dimens.icon * 0.9f),
                )
            }
            SwitchMark(checked = !category.hidden)
        }
    }
}

/** Interrupteur dessiné en gris, blanc, noir (la couleur est réservée au logo et au focus). */
@Composable
private fun SwitchMark(checked: Boolean) {
    val colors = Hs.colors
    val isTv = Hs.isTv
    val width = if (isTv) 30.dp else 40.dp
    val height = if (isTv) 18.dp else 24.dp
    val inset = 3.dp
    Box(
        Modifier
            .size(width, height)
            .clip(CircleShape)
            .background(if (checked) colors.text else colors.surface2)
            .border(1.5.dp, if (checked) colors.text else colors.textSecondary, CircleShape)
            .padding(inset),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(height - inset * 2)
                .clip(CircleShape)
                .background(if (checked) colors.background else colors.textSecondary),
        )
    }
}

// Contrôle parental -------------------------------------------------------------

@Composable
private fun ParentalPane(ui: ParentalUi, actions: SettingsActions) {
    val dimens = Hs.dimens
    val first = remember { FocusRequester() }
    Pane(stringResource(R.string.settings_section_parental)) {
        Paragraph(stringResource(R.string.settings_parental_intro))
        if (ui.error != null) {
            Text(ui.error.resolve(), style = Hs.type.body, color = Hs.colors.text)
        }
        if (ui.loaded && ui.error == null) {
            StatusLine(
                icon = if (ui.unlocked) Lucide.LockOpen else Lucide.Lock,
                text = stringResource(
                    when {
                        !ui.hasPin -> R.string.settings_parental_no_pin
                        ui.unlocked -> R.string.settings_parental_unlocked
                        else -> R.string.settings_parental_locked
                    },
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gap)) {
                HsButton(
                    text = stringResource(if (ui.hasPin) R.string.settings_parental_change else R.string.settings_parental_create),
                    onClick = if (ui.hasPin) actions::changePin else actions::createPin,
                    icon = Lucide.KeyRound,
                    style = ButtonStyle.Primary,
                    modifier = Modifier.focusRequester(first),
                )
                if (ui.unlocked) {
                    HsButton(
                        text = stringResource(R.string.settings_parental_lock_now),
                        onClick = {
                            actions.lockNow()
                            // Le bouton disparaît : le focus passe au premier.
                            runCatching { first.requestFocus() }
                        },
                        icon = Lucide.Lock,
                    )
                }
            }
            Text(stringResource(R.string.settings_parental_note), style = Hs.type.small, color = Hs.colors.textSecondary)
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, bold: Boolean = true) {
    val dimens = Hs.dimens
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Hs.colors.text, modifier = Modifier.size(dimens.icon))
        Text(
            text = text,
            style = if (bold) Hs.type.itemTitle else Hs.type.body,
            color = Hs.colors.text,
        )
    }
}

// Langue ------------------------------------------------------------------------

@Composable
private fun LanguagePane(language: LanguageChoice, actions: SettingsActions) {
    val focus = remember { LanguageChoice.entries.associateWith { FocusRequester() } }
    Pane(stringResource(R.string.settings_section_language)) {
        Paragraph(stringResource(R.string.settings_language_intro))
        Column(
            Modifier
                .widthIn(max = 420.dp)
                // Télécommande : en entrant, la langue retenue (pas la plus proche).
                .focusRestorer(focus.getValue(language))
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LanguageChoice.entries.forEach { choice ->
                OptionRow(
                    modifier = Modifier.focusRequester(focus.getValue(choice)),
                    label = stringResource(
                        when (choice) {
                            LanguageChoice.System -> R.string.settings_language_system
                            LanguageChoice.French -> R.string.settings_language_french
                            LanguageChoice.English -> R.string.settings_language_english
                        },
                    ),
                    selected = choice == language,
                    onClick = { actions.language(choice) },
                )
            }
        }
    }
}

/** Choix unique : coche sur l'option retenue. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = Hs.dimens
    HsSurface(
        onClick = onClick,
        selected = selected,
        focusScale = FocusScale.NONE,
        role = Role.RadioButton,
        contentPadding = PaddingValues(horizontal = if (dimens.isTv) 12.dp else 14.dp),
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.categoryRowHeight),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = Hs.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = LocalContentColor.current,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Lucide.Check, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(dimens.icon))
            }
        }
    }
}

// Catalogue ---------------------------------------------------------------------

@Composable
private fun CatalogPane(ui: CatalogUi, actions: SettingsActions) {
    val dimens = Hs.dimens
    Pane(stringResource(R.string.settings_section_catalog)) {
        Paragraph(stringResource(R.string.settings_catalog_intro))
        Paragraph(catalogUpdatedText(ui.updatedAt), secondary = false)
        // Toujours activé : un bouton désactivé perdrait le focus de la télécommande.
        HsButton(
            text = stringResource(if (ui.running) R.string.settings_catalog_updating else R.string.settings_catalog_update),
            onClick = actions::refreshCatalog,
            icon = Lucide.RefreshCw,
            style = ButtonStyle.Primary,
        )
        when {
            ui.running -> Paragraph(stringResource(R.string.settings_catalog_running))
            ui.error != null -> StatusLine(Lucide.CircleAlert, ui.error.resolve(), bold = false)
            ui.results != null -> Column(verticalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp)) {
                ui.results.forEach { result -> ResultLine(result) }
            }
        }
    }
}

/** « Dernière mise à jour : aujourd’hui à 14:02 », « hier à … », « le 27 octobre 2026 à … ». */
@Composable
private fun catalogUpdatedText(updatedAt: Long?): String {
    if (updatedAt == null) return stringResource(R.string.settings_catalog_never)
    val locale = currentLocale()
    val time = Formats.timeOfSeconds(updatedAt, locale)
    return when (Formats.daysAgo(updatedAt, System.currentTimeMillis() / 1000)) {
        0L -> stringResource(R.string.settings_catalog_updated_today, time)
        1L -> stringResource(R.string.settings_catalog_updated_yesterday, time)
        else -> stringResource(R.string.settings_catalog_updated_on, Formats.longDate(updatedAt, locale), time)
    }
}

@Composable
private fun ResultLine(result: SectionResult) {
    val dimens = Hs.dimens
    val count = NumberFormat.getIntegerInstance(currentLocale()).format(result.items)
    val quantity = result.items.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val text = when (result.outcome) {
        RefreshOutcome.Updated -> pluralStringResource(
            when (result.section) {
                Section.LIVE -> R.plurals.settings_catalog_updated_live
                Section.MOVIES -> R.plurals.settings_catalog_updated_movies
                Section.SERIES -> R.plurals.settings_catalog_updated_series
            },
            quantity,
            count,
        )
        RefreshOutcome.KeptCache -> stringResource(R.string.settings_catalog_kept)
        RefreshOutcome.Failed -> stringResource(R.string.settings_catalog_failed)
        RefreshOutcome.UpToDate -> stringResource(R.string.settings_catalog_up_to_date)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (result.outcome) {
                RefreshOutcome.Failed -> Lucide.CircleAlert
                // Pas retenté (moins d'une minute après un essai, réussi ou non).
                RefreshOutcome.UpToDate -> Lucide.Clock
                else -> Lucide.CircleCheck
            },
            contentDescription = null,
            tint = Hs.colors.text,
            modifier = Modifier.size(dimens.icon),
        )
        Text(
            text = stringResource(result.section.tab),
            style = Hs.type.itemTitle,
            maxLines = 1,
            modifier = Modifier.width(if (dimens.isTv) 64.dp else 80.dp),
        )
        Text(text, style = Hs.type.body, color = Hs.colors.textSecondary)
    }
}

// Abonnement --------------------------------------------------------------------

@Composable
private fun SubscriptionPane(ui: AccountUi, signingOut: Boolean, actions: SettingsActions) {
    val dimens = Hs.dimens
    Pane(stringResource(R.string.settings_section_subscription)) {
        val subscription = ui.subscription
        if (subscription == null) {
            Paragraph(stringResource(R.string.settings_subscription_unknown))
        } else {
            val expired = subscription is Subscription.Expired
            Text(
                text = subscription.text(),
                style = if (expired) Hs.type.body.copy(fontWeight = FontWeight.Bold) else Hs.type.body,
                color = Hs.colors.text,
            )
        }
        if (ui.maxConnections != null) {
            Paragraph(stringResource(R.string.settings_subscription_connections, ui.maxConnections.toInt()))
        }
        // Autre adresse du même fournisseur : rien n'est effacé (spec).
        HsButton(
            text = stringResource(R.string.settings_change_server),
            onClick = actions::changeServer,
            icon = Lucide.Server,
        )
        Box(
            Modifier
                .padding(vertical = dimens.gapSmall)
                .fillMaxWidth()
                .heightIn(min = 1.dp)
                .background(Hs.colors.separator),
        )
        Paragraph(stringResource(R.string.settings_sign_out_warning))
        HsButton(
            text = stringResource(if (signingOut) R.string.settings_signing_out else R.string.settings_sign_out),
            onClick = actions::signOut,
            icon = Lucide.LogOut,
        )
    }
}

// À propos ----------------------------------------------------------------------

@Composable
private fun AboutPane(appVersion: String, coreVersion: String?, actions: SettingsActions) {
    val dimens = Hs.dimens
    Pane(stringResource(R.string.settings_section_about)) {
        Logo(size = if (dimens.isTv) 26.dp else 30.dp)
        Text(
            text = if (coreVersion != null) {
                stringResource(R.string.settings_about_versions, appVersion, coreVersion)
            } else {
                stringResource(R.string.settings_about_version, appVersion)
            },
            style = Hs.type.body,
            color = Hs.colors.textSecondary,
        )
        Paragraph(stringResource(R.string.settings_about_license), secondary = false)
        Paragraph(stringResource(R.string.settings_about_no_content), secondary = false)
        Text(
            text = stringResource(R.string.settings_about_credits),
            style = Hs.type.itemTitle,
            modifier = Modifier
                .padding(top = dimens.gapSmall)
                .semantics { heading() },
        )
        Column(
            Modifier.focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            License.entries.forEach { license ->
                CreditRow(
                    name = stringResource(license.title),
                    license = stringResource(license.licenseName),
                    onClick = { actions.showLicense(license) },
                )
            }
            CreditRow(stringResource(R.string.settings_credit_androidx), stringResource(R.string.settings_credit_androidx_license))
            CreditRow(stringResource(R.string.settings_credit_ffmpeg), stringResource(R.string.settings_credit_ffmpeg_license))
        }
    }
}

/** Crédit : nom et licence ; ouvre le texte de la licence s'il est fourni. */
@Composable
private fun CreditRow(name: String, license: String, onClick: (() -> Unit)? = null) {
    val dimens = Hs.dimens
    val padding = PaddingValues(horizontal = if (dimens.isTv) 12.dp else 14.dp, vertical = if (dimens.isTv) 5.dp else 6.dp)
    val content = @Composable {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (dimens.isTv) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = Hs.type.body,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(license, style = Hs.type.small, color = Hs.colors.textSecondary, maxLines = 1)
            }
            if (onClick != null) {
                Text(
                    stringResource(R.string.settings_about_show_license),
                    style = Hs.type.small,
                    color = LocalContentColor.current,
                    maxLines = 1,
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(dimens.icon))
            }
        }
    }
    if (onClick == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.categoryRowHeight)
                .padding(padding),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    } else {
        HsSurface(
            onClick = onClick,
            focusScale = FocusScale.NONE,
            color = Color.Transparent,
            contentPadding = padding,
            contentAlignment = Alignment.CenterStart,
            onClickLabel = stringResource(R.string.settings_about_show_license),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.categoryRowHeight),
        ) { content() }
    }
}

/**
 * Demande le focus dès que l'élément est composé (une liste compose ses
 * éléments à la mise en page, après le premier passage).
 */
private suspend fun FocusRequester.requestFocusWhenReady(): Boolean {
    repeat(FOCUS_ATTEMPTS) {
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return true
        withFrameNanos { }
    }
    return false
}

private const val FOCUS_ATTEMPTS = 5

package io.github.shweepsi.heystream.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shweepsi.heystream.core.AccountInfo
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SectionRefresh
import io.github.shweepsi.heystream.core.coreVersion
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.data.RefreshState
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.home.Subscription
import io.github.shweepsi.heystream.ui.home.subscriptionOf
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Rubriques des réglages, dans l'ordre de la liste de gauche. */
enum class SettingsSection { HiddenCategories, Parental, Language, Catalog, Subscription, About }

/** Langue de l'app ; [tags] : liste BCP 47 pour AppCompat (vide : celle du système). */
enum class LanguageChoice(val tags: String) {
    System(""),
    French("fr"),
    English("en"),
    ;

    companion object {
        /** Choix d'après les langues retenues par AppCompat (`toLanguageTags()`). */
        fun of(tags: String): LanguageChoice {
            val first = tags.split(',').firstOrNull()?.trim()?.lowercase().orEmpty()
            return when {
                first.isEmpty() -> System
                first == "fr" || first.startsWith("fr-") -> French
                first == "en" || first.startsWith("en-") -> English
                else -> System
            }
        }
    }
}

/** Une catégorie et son interrupteur « affichée / masquée ». */
@Immutable
data class CategoryToggle(val id: String, val name: String, val hidden: Boolean, val locked: Boolean)

fun CategoryEntry.toToggle() = CategoryToggle(id = id, name = name, hidden = hidden, locked = locked)

/** Catégories masquées : section choisie et ses catégories (`null` pendant le chargement). */
@Immutable
data class CategoriesUi(
    val section: Section = Section.LIVE,
    val items: List<CategoryToggle>? = null,
    val downloading: Boolean = false,
    val error: UiText? = null,
)

/** Contrôle parental. */
@Immutable
data class ParentalUi(
    val loaded: Boolean = false,
    val hasPin: Boolean = false,
    val unlocked: Boolean = false,
    val error: UiText? = null,
)

/** Résultat d'une section après une mise à jour manuelle. */
enum class RefreshOutcome { Updated, KeptCache, Failed, UpToDate }

@Immutable
data class SectionResult(val section: Section, val outcome: RefreshOutcome, val items: Long)

fun SectionRefresh.toResult(): SectionResult = SectionResult(
    section = section,
    outcome = when {
        failed -> RefreshOutcome.Failed
        keptCache -> RefreshOutcome.KeptCache
        refreshed -> RefreshOutcome.Updated
        else -> RefreshOutcome.UpToDate
    },
    items = items.coerceAtMost(Long.MAX_VALUE.toULong()).toLong(),
)

/** Mise à jour du catalogue : en cours, puis résultat par section ou erreur. */
@Immutable
data class CatalogUi(
    val running: Boolean = false,
    val results: List<SectionResult>? = null,
    val error: UiText? = null,
)

/**
 * État de la rubrique Catalogue. Le résultat n'est montré qu'après une
 * mise à jour demandée ici ([requested]), pas après la mise à jour
 * quotidienne en arrière-plan.
 */
fun catalogUiOf(refresh: RefreshState, requested: Boolean): CatalogUi = when (refresh) {
    RefreshState.Running -> CatalogUi(running = true)
    is RefreshState.Done -> if (requested) CatalogUi(results = refresh.sections.map { it.toResult() }) else CatalogUi()
    is RefreshState.Failed -> if (requested) CatalogUi(error = refresh.error.toUiText()) else CatalogUi()
    RefreshState.Idle -> CatalogUi()
}

/** Abonnement d'après la dernière connexion réussie (connu hors ligne). */
@Immutable
data class AccountUi(val subscription: Subscription? = null, val maxConnections: Long? = null)

fun accountUiOf(info: AccountInfo?, nowSecs: Long): AccountUi =
    AccountUi(subscription = subscriptionOf(info, nowSecs), maxConnections = info?.maxConnections?.takeIf { it > 0 })

@Immutable
data class SettingsState(
    val section: SettingsSection = SettingsSection.HiddenCategories,
    val categories: CategoriesUi = CategoriesUi(),
    val parental: ParentalUi = ParentalUi(),
    val catalog: CatalogUi = CatalogUi(),
    val account: AccountUi = AccountUi(),
    val signingOut: Boolean = false,
    /** Déconnexion terminée : l'écran passe à la connexion. */
    val signedOut: Boolean = false,
    val coreVersion: String? = null,
)

class SettingsViewModel(private val session: CoreSession, private val player: PlayerController) : ViewModel() {
    private val section = MutableStateFlow(SettingsSection.HiddenCategories)
    private val categorySection = MutableStateFlow(Section.LIVE)
    private val categories = MutableStateFlow(CategoriesUi())
    private val parental = MutableStateFlow(ParentalUi())
    private val catalogRequested = MutableStateFlow(false)
    private val signOut = MutableStateFlow(SignOutState())
    private val core = MutableStateFlow<String?>(null)

    private data class SignOutState(val running: Boolean = false, val done: Boolean = false)

    private val running = session.refresh.map { it is RefreshState.Running }.distinctUntilChanged()

    private val catalog = combine(session.refresh, catalogRequested, ::catalogUiOf)

    private val account = session.account.map { accountUiOf(it, System.currentTimeMillis() / 1000) }

    private val extras = combine(account, signOut, core, ::Triple)

    val state: StateFlow<SettingsState> = combine(section, categories, parental, catalog, extras) { section, categories, parental, catalog, extras ->
        val (account, signOut, core) = extras
        SettingsState(
            section = section,
            categories = categories,
            parental = parental,
            catalog = catalog,
            account = account,
            signingOut = signOut.running,
            signedOut = signOut.done,
            coreVersion = core,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    init {
        viewModelScope.launch {
            combine(categorySection, running, session.catalogVersion, session.userDataVersion) { section, running, _, _ -> section to running }
                .collectLatest { (section, running) -> loadCategories(section, running) }
        }
        viewModelScope.launch {
            session.userDataVersion.collectLatest { loadParental() }
        }
        viewModelScope.launch {
            // La bibliothèque native est chargée avec le cœur de la session.
            core.value = runCatching { session.withCore { coreVersion() } }.getOrNull()
        }
    }

    fun select(value: SettingsSection) {
        section.value = value
    }

    fun selectCategorySection(value: Section) {
        categorySection.value = value
    }

    /** Masque ou affiche une catégorie ; l'interrupteur change tout de suite. */
    fun toggle(category: CategoryToggle) {
        val target = categories.value.section
        val hidden = !category.hidden
        categories.update { ui ->
            if (ui.section != target) ui else ui.copy(items = ui.items?.map { if (it.id == category.id) it.copy(hidden = hidden) else it })
        }
        viewModelScope.launch {
            try {
                session.withCore { it.setCategoryHidden(target, category.id, hidden) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                categories.update { it.copy(error = failure.toUiText()) }
            }
            session.notifyUserDataChanged()
        }
    }

    /** Verrouille les catégories adultes jusqu'au prochain code. */
    fun lockNow() {
        viewModelScope.launch {
            runCatching { session.withCore { it.lockParental() } }
            session.notifyUserDataChanged()
        }
    }

    /** Mise à jour manuelle de tout le catalogue (continue si l'on quitte l'écran). */
    fun refreshCatalog() {
        if (session.refresh.value is RefreshState.Running) return
        catalogRequested.value = true
        session.refreshInBackground(force = true)
    }

    /** Déconnexion : efface le cache de l'abonnement sur l'appareil, ferme le flux. */
    fun signOut() {
        if (signOut.value.running) return
        signOut.value = SignOutState(running = true)
        viewModelScope.launch {
            try {
                session.signOut()
                player.stop()
            } finally {
                signOut.value = SignOutState(running = false, done = true)
            }
        }
    }

    private suspend fun loadCategories(section: Section, running: Boolean) {
        if (categories.value.section != section) categories.value = CategoriesUi(section = section)
        categories.value = try {
            val items = session.withCore { it.allCategories(section) }.map { it.toToggle() }
            CategoriesUi(section = section, items = items, downloading = running && items.isEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            CategoriesUi(section = section, items = emptyList(), error = failure.toUiText())
        }
    }

    private suspend fun loadParental() {
        parental.value = try {
            val (hasPin, unlocked) = session.withCore { it.hasPin() to it.isParentalUnlocked() }
            ParentalUi(loaded = true, hasPin = hasPin, unlocked = unlocked)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            ParentalUi(loaded = true, error = failure.toUiText())
        }
    }
}

package io.github.shweepsi.heystream.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.InvalidatingPagingSourceFactory
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.LiveFormat
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.CatalogPaging
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.data.OffsetPagingSource
import io.github.shweepsi.heystream.data.RefreshState
import io.github.shweepsi.heystream.data.countFavorites
import io.github.shweepsi.heystream.player.LiveTimeshift
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.player.TimeshiftHolder
import io.github.shweepsi.heystream.core.GuideProgramme
import io.github.shweepsi.heystream.core.Recording
import io.github.shweepsi.heystream.core.ScheduleRequest
import io.github.shweepsi.heystream.recording.RecordingRepository
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.components.PinPurpose
import io.github.shweepsi.heystream.ui.detail.visibleItem
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Direct : catégories, chaînes (paginées) et aperçu. L'aperçu et le plein
 * écran sont le même lecteur ([PlayerController]) : un seul flux ouvert.
 * Toutes les données viennent du cœur (cache) ; seul le programme en cours et
 * suivant de la chaîne choisie peut demander le fournisseur (au plus une fois
 * toutes les 15 min par chaîne, règle du cœur).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(
    private val session: CoreSession,
    private val player: PlayerController,
    private val initialChannelId: Long?,
    /** Enregistrement programmé (Android) ; absent dans les tests sans cœur natif. */
    private val recordings: RecordingRepository? = null,
    /** L'alarme exacte est autorisée (sinon, explication à la première programmation). */
    private val canScheduleExact: () -> Boolean = { true },
    /** Différé (Android, avec le disque des enregistrements) ; absent dans les tests sans cœur natif. */
    private val timeshift: TimeshiftHolder? = null,
) : ViewModel(), LiveActions {
    private val _state = MutableStateFlow(LiveUiState(recordingEnabled = recordings != null))
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    /** Source de la liste affichée : invalidée pour recharger sur place (défilement et focus gardés). */
    private var sources: InvalidatingPagingSourceFactory<Int, CatalogItem>? = null

    /** Chaînes de la catégorie ouverte, page par page. */
    val channels: Flow<PagingData<CatalogItem>> = _state
        .map { it.selected }
        .filterNotNull()
        .distinctUntilChanged()
        .flatMapLatest { key -> Pager(CatalogPaging.config, pagingSourceFactory = sourceFactory(key)).flow }
        .cachedIn(viewModelScope)

    /** Rechargement des catégories demandé par l'écran (« Réessayer »). */
    private val reloads = MutableStateFlow(0)

    /** Version des données de l'utilisateur produite par nos propres favoris (pas de rechargement de liste). */
    private var ownUserDataVersion = -1L

    private val visibleIds = MutableStateFlow<List<Long>>(emptyList())

    /** Nouveau programme en cache (après `nowNext`) : relire les lignes visibles. */
    private val epgChanges = MutableStateFlow(0)

    private var previewError: UiText? = null
    private var startJob: Job? = null
    private var nowNextJob: Job? = null
    private var overlayJob: Job? = null
    private var messageJob: Job? = null

    /** Reverrouillages du contrôle parental déjà pris en compte ([CoreSession.relocks]). */
    private var seenRelocks = session.relocks.value

    /** Vérification du contrôle parental avant la reprise ([checkHeld]). */
    private var holdJob: Job? = null

    init {
        viewModelScope.launch {
            val initial = initialChannelId?.let { id -> attempt { session.withCore { it.item(Section.LIVE, id) } }.getOrNull() }
            if (initial != null) {
                startPreview(initial.toPreview(), addRecent = true)
                enterFullscreen()
            }
            var preferred = initial?.categoryId
            combine(session.catalogVersion, session.userDataVersion, reloads) { _, _, _ -> }
                .collectLatest {
                    loadCategories(preferred)
                    preferred = null
                }
        }
        viewModelScope.launch {
            session.catalogVersion.drop(1).collect { sources?.invalidate() }
        }
        viewModelScope.launch {
            // Favori basculé ici : la liste reste telle quelle (la ligne garde le focus) ;
            // tout autre changement (code PIN, réglages) la recharge sur place.
            session.userDataVersion.drop(1).collect { version ->
                if (version != ownUserDataVersion) sources?.invalidate()
            }
        }
        viewModelScope.launch {
            session.refresh.collect { refresh ->
                _state.update { it.copy(downloading = refresh is RefreshState.Running) }
            }
        }
        viewModelScope.launch {
            player.status.collect { updatePlayback() }
        }
        recordings?.let { repo ->
            viewModelScope.launch {
                combine(repo.version, session.userDataVersion) { _, _ -> }.collectLatest { loadRecordings() }
            }
            viewModelScope.launch {
                // Enregistrement qui commence ou finit sur la chaîne en aperçu : le
                // fichier remplace le flux, puis le flux revient.
                repo.active.drop(1).collect { active ->
                    val preview = _state.value.preview ?: return@collect
                    // Un enregistrement qui suit le différé de cette chaîne ne change rien.
                    val fromFile = active?.streamId == preview.id && !active.following
                    if (fromFile != _state.value.fromRecording || (active == null && player.status.value.blocked)) {
                        startPreview(preview, addRecent = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            player.held.collect { hold -> if (hold != 0L) checkHeld() }
        }
        if (timeshift != null) {
            viewModelScope.launch {
                while (isActive) {
                    tickTimeshift()
                    delay(TIMESHIFT_TICK_MS)
                }
            }
        }
        viewModelScope.launch {
            combine(visibleIds.debounce(VISIBLE_DEBOUNCE_MS), epgChanges, minuteTicks()) { ids, _, _ -> ids }
                .collectLatest { ids -> loadNowTitles(ids) }
        }
        viewModelScope.launch {
            // Programme de l'aperçu terminé (on regarde parfois des heures) : relu, sinon
            // l'aperçu et le bandeau du plein écran montreraient un programme passé.
            minuteTicks().collect {
                val preview = _state.value.preview
                if (preview != null && nowNextJob?.isActive != true && epgOutdated(preview, System.currentTimeMillis() / 1000)) {
                    loadNowNext(preview.id)
                }
            }
        }
    }

    private fun sourceFactory(key: CategoryKey): InvalidatingPagingSourceFactory<Int, CatalogItem> =
        InvalidatingPagingSourceFactory {
            when (key) {
                // Pas de chaînes : la liste des enregistrements s'affiche à la place.
                CategoryKey.Recordings -> OffsetPagingSource(total = { 0 }, load = { _, _ -> emptyList() })
                CategoryKey.Favorites -> OffsetPagingSource(
                    total = { session.withCore { countFavorites(it, Section.LIVE).toInt() } },
                    load = { offset, limit ->
                        session.withCore { it.favorites(Section.LIVE, offset.toUInt(), limit.toUInt()) }
                    },
                )
                CategoryKey.Recents -> OffsetPagingSource(
                    total = { session.withCore { it.recents(Section.LIVE, RECENTS_LIMIT.toUInt()).size } },
                    load = { offset, limit ->
                        session.withCore { it.recents(Section.LIVE, RECENTS_LIMIT.toUInt()) }.drop(offset).take(limit)
                    },
                )
                is CategoryKey.Provider -> OffsetPagingSource(
                    total = { session.withCore { it.count(Section.LIVE, key.id).toInt() } },
                    load = { offset, limit ->
                        session.withCore { it.items(Section.LIVE, key.id, offset.toUInt(), limit.toUInt()) }
                    },
                )
            }
        }.also { sources = it }

    private class Loaded(val categories: List<LiveCategory>, val favorites: Set<Long>)

    private suspend fun loadCategories(preferred: String?) {
        val result = attempt {
            session.withCore { core ->
                val entries = core.categories(Section.LIVE)
                val favorites = core.favorites(Section.LIVE, 0u, FAVORITES_LIMIT.toUInt()).mapTo(HashSet()) { it.id }
                val recents = core.recents(Section.LIVE, RECENTS_LIMIT.toUInt()).size
                // Jamais de comptage d'une catégorie verrouillée (le cœur le refuse).
                val counts = entries.filter { !it.locked }.mapNotNull { entry ->
                    runCatching { entry.id to core.count(Section.LIVE, entry.id).toLong() }.getOrNull()
                }.toMap()
                val recordingCount = recordings?.let { repo ->
                    val unlocked = core.isParentalUnlocked()
                    runCatching { repo.storeBlocking().let { it.scheduled(unlocked).size + it.recorded(unlocked).size } }.getOrDefault(0)
                } ?: 0
                Loaded(liveCategories(entries, counts, favorites.size, recents, recordingCount), favorites)
            }
        }
        result.onSuccess { loaded ->
            _state.update { current ->
                val ready = CategoriesUi.Ready(loaded.categories)
                val selected = when {
                    current.selected != null -> keepCategory(current.selected, loaded.categories)
                    // Catalogue pas encore là : attendre pour ouvrir sa première catégorie.
                    ready.catalogEmpty -> null
                    else -> defaultCategory(loaded.categories, preferred)
                }
                current.copy(categories = ready, favoriteIds = loaded.favorites, selected = selected)
            }
        }.onFailure { error ->
            // Un rechargement raté garde les catégories déjà affichées.
            if (_state.value.categories !is CategoriesUi.Ready) {
                _state.update { it.copy(categories = CategoriesUi.Failed(error.toUiText())) }
            }
        }
    }

    private suspend fun loadNowTitles(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Lecture du cache seulement : aucune requête au fournisseur pour remplir la liste.
        val found = attempt { session.withCore { it.cachedNow(ids) } }.getOrNull() ?: return
        val titles = found.associate { it.streamId to it.programme.title }
        _state.update { it.copy(nowTitles = mergeNowTitles(it.nowTitles, ids, titles)) }
    }

    override fun onSelectCategory(category: LiveCategory) {
        val key = category.key
        if (key is CategoryKey.Provider && category.locked) {
            viewModelScope.launch {
                val hasPin = attempt { session.withCore { it.hasPin() } }.getOrDefault(true)
                _state.update {
                    it.copy(pinRequest = PinRequest(key.id, if (hasPin) PinPurpose.Unlock else PinPurpose.Create))
                }
            }
            return
        }
        // Catégorie déjà ouverte : rechargée sur place (favori retiré, récents réordonnés).
        if (_state.value.selected == key) {
            sources?.invalidate()
            return
        }
        _state.update { it.copy(selected = key) }
    }

    /** Code bon : la catégorie s'ouvre (les catégories se rechargent, déverrouillées). */
    fun onPinSuccess() {
        val request = _state.value.pinRequest ?: return
        _state.update { it.copy(pinRequest = null, selected = CategoryKey.Provider(request.categoryId)) }
    }

    fun onPinDismiss() {
        _state.update { it.copy(pinRequest = null) }
    }

    override fun onChannelClick(channel: CatalogItem) {
        when (channelClick(_state.value.preview?.id, channel.id)) {
            ChannelClick.Fullscreen -> enterFullscreen()
            ChannelClick.Preview -> startPreview(channel.toPreview(), addRecent = true)
        }
    }

    override fun onToggleFavorite(channel: CatalogItem) {
        viewModelScope.launch {
            attempt { session.withCore { it.toggleFavorite(Section.LIVE, channel.id) } }
                .onSuccess { favorite ->
                    _state.update { current ->
                        val ids = if (favorite) current.favoriteIds + channel.id else current.favoriteIds - channel.id
                        current.copy(favoriteIds = ids)
                    }
                    // Retenue avant la notification : ses collecteurs peuvent s'exécuter aussitôt.
                    ownUserDataVersion = session.userDataVersion.value + 1
                    session.notifyUserDataChanged()
                    val text = if (favorite) R.string.live_favorite_added else R.string.live_favorite_removed
                    showMessage(UiText(text, listOf(channel.name)))
                }
                .onFailure { showMessage(it.toUiText()) }
        }
    }

    override fun onVisibleChannels(ids: List<Long>) {
        visibleIds.value = ids
    }

    override fun onVideoClick() {
        val current = _state.value
        when {
            current.preview == null -> Unit
            !current.fullscreen -> enterFullscreen()
            current.overlayVisible -> hideOverlay()
            else -> showOverlay()
        }
    }

    override fun onFullscreenKey(ok: Boolean) {
        when {
            ok && _state.value.playback is LivePlayback.Failed -> onRetryPlayback()
            // Différé : OK met en pause, comme les films.
            ok && player.status.value.timeshift -> onTimeshiftPause()
            else -> showOverlay()
        }
    }

    // Différé ------------------------------------------------------------------

    /** Saut cumulé en attente (secondes), appliqué après le dernier appui. */
    private var pendingSeekSecs: Long? = null
    private var seekJob: Job? = null

    override fun onTimeshiftSeek(forward: Boolean) {
        if (!player.status.value.timeshift) return
        val step = if (forward) PlayerController.SEEK_FORWARD_MS / 1000 else -PlayerController.SEEK_BACK_MS / 1000
        pendingSeekSecs = (pendingSeekSecs ?: 0L) + step
        showOverlay()
        tickTimeshift()
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(io.github.shweepsi.heystream.ui.player.SEEK_COMMIT_MS)
            val delta = pendingSeekSecs ?: return@launch
            pendingSeekSecs = null
            val position = player.timeshiftPosition()
            if (position != null && delta != 0L) player.seekTimeshift(timeshiftTarget(position, delta))
            tickTimeshift()
        }
    }

    override fun onTimeshiftPause() {
        if (!player.status.value.timeshift) return
        player.toggleTimeshiftPause()
        showOverlay()
        tickTimeshift()
    }

    override fun onTimeshiftLive() {
        seekJob?.cancel()
        pendingSeekSecs = null
        player.timeshiftLive()
        showOverlay()
        tickTimeshift()
    }

    /**
     * Barre du différé, et pause longue : une minute avant que le moment en
     * pause ne soit effacé, l'avertissement ; puis la lecture reprend seule
     * au plus ancien moment gardé.
     */
    private fun tickTimeshift() {
        val position = if (player.status.value.timeshift) player.timeshiftPosition() else null
        if (position == null) {
            if (_state.value.timeshift != null) _state.update { it.copy(timeshift = null) }
            return
        }
        val eraseIn = position.eraseInSecs
        if (!position.playing && eraseIn != null && eraseIn <= 0.0) {
            player.resumeTimeshiftFromOldest()
        }
        val ui = timeshiftUi(position, pendingSeekSecs, eraseIn)
        if (ui != _state.value.timeshift) _state.update { it.copy(timeshift = ui) }
    }

    /**
     * Direct depuis le différé quand c'est possible : TS autorisé, disque des
     * enregistrements, connexion libre. `false` : lecture réseau à la place
     * (compte HLS seul, pas de disque, premières données qui n'arrivent pas).
     */
    private suspend fun startTimeshift(id: Long, url: String): Boolean {
        val holder = timeshift ?: return false
        val repo = recordings ?: return false
        var handed = false
        var live: LiveTimeshift? = holder.reuse(id)
        try {
            if (live == null) {
                if (player.recordingActive) return false
                if (!attempt { session.withCore { it.canRecord() } }.getOrDefault(false)) return false
                val dir = attempt { repo.ensureDirectory() }.getOrNull() ?: return false
                val core = session.openedCore() ?: return false
                player.opening()
                live = attempt { holder.start(core, id, dir) }.getOrNull() ?: return false
            }
            val session = live
            val ready = withTimeoutOrNull(TIMESHIFT_FIRST_DATA_MS) {
                while (session.window() == null && session.alive) delay(TIMESHIFT_POLL_MS)
                session.window() != null
            } == true
            // Une autre chaîne a été choisie entre-temps : celle-ci ne s'ouvre pas.
            if (_state.value.preview?.id != id) return true
            if (!ready) return false
            player.playTimeshift(
                session,
                at = null,
                reopen = { _state.value.preview?.takeIf { it.id == id }?.let { startPreview(it, addRecent = false) } },
                lost = { if (_state.value.preview?.id == id) player.playLive(url) },
            )
            handed = true
            return true
        } finally {
            // Pas confié au lecteur (autre chaîne, échec, annulation) : relâché.
            if (!handed) live?.release()
        }
    }

    override fun onExitFullscreen() {
        overlayJob?.cancel()
        _state.update { it.copy(fullscreen = false, overlayVisible = false) }
    }

    override fun onRetryPlayback() {
        val preview = _state.value.preview ?: return
        if (previewError != null || !player.status.value.active) startPreview(preview, addRecent = false) else player.retry()
    }

    override fun onReloadCategories() {
        _state.update { if (it.categories is CategoriesUi.Failed) it.copy(categories = CategoriesUi.Loading) else it }
        reloads.update { it + 1 }
    }

    override fun onRefreshCatalog() {
        session.refreshInBackground(force = true)
    }

    /**
     * L'écran se ferme (Retour) ou quitte la composition : le flux est fermé, et
     * une chaîne en cours d'ouverture ne l'est plus (jamais de flux sans écran).
     */
    fun onLeave() {
        startJob?.cancel()
        nowNextJob?.cancel()
        // Lecteur confié à un enregistrement (l'écran disparaît après que sa
        // lecture a commencé) : on n'y touche plus.
        if (!handedOver) player.stop()
    }

    /** Le lecteur unique lit un enregistrement ouvert depuis cet écran. */
    private var handedOver = false

    /**
     * L'app a quitté l'écran ([PlayerController.hold]) : une fois le
     * reverrouillage posé, une chaîne d'aperçu désormais cachée par le
     * contrôle parental disparaît sans être rouverte ([dropPreview]) ; sinon
     * le flux reprend comme avant.
     */
    private fun checkHeld() {
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            session.awaitRelock()
            val relocks = session.relocks.value
            val id = _state.value.preview?.id
            val hidden = relocks != seenRelocks && id != null && hiddenByLock(id)
            seenRelocks = relocks
            if (hidden && _state.value.preview?.id == id) dropPreview()
            // Vérifié : la chaîne reprend, ou plus rien n'est à reprendre (flux fermé).
            player.releaseHold()
        }
    }

    /** Même règle que les fiches ([visibleItem]) : la chaîne n'est plus visible, verrou fermé. */
    private suspend fun hiddenByLock(id: Long): Boolean =
        attempt { session.withCore { it.visibleItem(Section.LIVE, id) } }.exceptionOrNull() is HeyStreamException.ParentalLock

    /** Chaîne verrouillée : ni nom, ni logo, ni programme, ni vidéo, ni plein écran. */
    private fun dropPreview() {
        startJob?.cancel()
        nowNextJob?.cancel()
        overlayJob?.cancel()
        previewError = null
        player.stop()
        _state.update { it.copy(preview = null, fullscreen = false, overlayVisible = false) }
        updatePlayback()
    }

    /** L'écran revient (recréation de l'activité) : l'aperçu reprend s'il a été arrêté. */
    fun resumeIfStopped() {
        val preview = _state.value.preview ?: return
        if (startJob?.isActive != true && !player.status.value.active) startPreview(preview, addRecent = false)
    }

    private fun startPreview(preview: LivePreview, addRecent: Boolean) {
        handedOver = false
        val id = preview.id
        previewError = null
        // Une chaîne choisie remplace la précédente avant même son ouverture : l'ancien
        // flux est fermé tout de suite, et son état (échec, reconnexion) ne s'affiche
        // pas sur la nouvelle chaîne.
        startJob?.cancel()
        player.stop()
        _state.update { it.copy(preview = preview) }
        updatePlayback()
        val active = recordings?.active?.value
        _state.update { it.copy(fromRecording = active?.streamId == id && !active.following) }
        if (active != null && active.streamId == id && !active.following) {
            // La chaîne enregistrée se regarde depuis son fichier (quelques secondes de
            // retard), sans seconde connexion.
            startJob = viewModelScope.launch {
                val start = (java.io.File(active.path).length() - LIVE_EDGE_BYTES).coerceAtLeast(0)
                player.playRecording(active.path, growing = { recordings.active.value?.id == active.id }, startOffset = start, live = true)
                if (addRecent) runCatching { session.withCore { core -> core.addRecent(Section.LIVE, id) } }
            }
            loadNowNext(id)
            return
        }
        startJob = viewModelScope.launch {
            // L'URL d'abord, l'écriture des récents ensuite : pendant un
            // rafraîchissement du catalogue, une écriture attendrait sa fin et
            // retarderait le zapping.
            val url = attempt {
                session.withCore { core ->
                    // Liste pas encore relue après un reverrouillage : une chaîne
                    // désormais cachée ne s'ouvre pas (règle du cœur).
                    core.visibleItem(Section.LIVE, id)
                    core.liveUrl(id, LiveFormat.TS)
                }
            }
            // Vérification du contrôle parental en cours (retour dans l'app) : elle passe d'abord.
            holdJob?.join()
            // Une autre chaîne a été choisie entre-temps (ou celle-ci est verrouillée) : pas ouverte.
            if (_state.value.preview?.id != id) return@launch
            if (url.exceptionOrNull() is HeyStreamException.ParentalLock) {
                dropPreview()
                return@launch
            }
            url.onSuccess { liveUrl -> if (!startTimeshift(id, liveUrl)) player.playLive(liveUrl) }
                .onFailure { error ->
                    previewError = error.toUiText()
                    player.stop()
                    updatePlayback()
                }
            if (addRecent) {
                runCatching { session.withCore { core -> core.addRecent(Section.LIVE, id) } }
                updateRecentsCount()
            }
        }
        loadNowNext(id)
    }

    /** Programme en cours et suivant de [id] (cache du cœur, fournisseur au plus une fois par 15 min). */
    private fun loadNowNext(id: Long) {
        nowNextJob?.cancel()
        nowNextJob = viewModelScope.launch {
            // Erreurs ignorées : sans programme, rien ne s'affiche.
            val nowNext = attempt { session.withCore { it.nowNext(id) } }.getOrNull() ?: return@launch
            _state.update { current ->
                val shown = current.preview
                if (shown?.id == id) current.copy(preview = shown.withEpg(nowNext)) else current
            }
            epgChanges.update { it + 1 }
            updateScheduledStarts()
        }
    }

    /** Récents : seul leur nombre change (la liste ouverte n'est pas réordonnée sous le focus). */
    private suspend fun updateRecentsCount() {
        val count = attempt { session.withCore { it.recents(Section.LIVE, RECENTS_LIMIT.toUInt()).size } }.getOrNull() ?: return
        _state.update { current ->
            val ready = current.categories as? CategoriesUi.Ready ?: return@update current
            val categories = ready.categories.map {
                if (it.key == CategoryKey.Recents) it.copy(count = count.toLong()) else it
            }
            current.copy(categories = CategoriesUi.Ready(categories))
        }
    }

    private fun updatePlayback() {
        val status = player.status.value
        _state.update { it.copy(playback = livePlaybackOf(status, it.preview != null, previewError)) }
    }

    private fun enterFullscreen() {
        _state.update { it.copy(fullscreen = true) }
        showOverlay()
    }

    private fun showOverlay() {
        _state.update { it.copy(overlayVisible = true) }
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(OVERLAY_MS)
            _state.update { it.copy(overlayVisible = false) }
        }
    }

    private fun hideOverlay() {
        overlayJob?.cancel()
        _state.update { it.copy(overlayVisible = false) }
    }

    private fun showMessage(text: UiText) {
        _state.update { it.copy(message = text) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            _state.update { it.copy(message = null) }
        }
    }

    // Enregistrement ----------------------------------------------------------

    /** Programmés et en cours, pour marquer le guide et trouver ce qu'on annule. */
    private var planned: List<Recording> = emptyList()

    private suspend fun loadRecordings() {
        val repo = recordings ?: return
        val unlocked = attempt { session.withCore { it.isParentalUnlocked() } }.getOrDefault(false)
        val loaded = attempt {
            repo.withStore { store ->
                // Tous les programmés (y compris adultes) : le guide d'une chaîne
                // ouverte les marque ; la liste, elle, suit le verrou.
                val allPlanned = store.scheduled(true)
                val rows = (store.scheduled(unlocked) + store.recorded(unlocked)).map { it.toRow() }
                Triple(allPlanned, rows, runCatching { store.usage() }.getOrNull())
            }
        }.getOrNull() ?: return
        val (allPlanned, rows, usage) = loaded
        planned = allPlanned
        val noDisk = attempt { repo.ensureDirectory() }.getOrNull() == null
        _state.update { current ->
            val ready = current.categories as? CategoriesUi.Ready
            val categories = ready?.let {
                CategoriesUi.Ready(it.categories.map { c -> if (c.key == CategoryKey.Recordings) c.copy(count = rows.size.toLong()) else c })
            } ?: current.categories
            current.copy(
                recordings = RecordingsUi(rows = rows, usage = usage, noDisk = noDisk, loaded = true),
                categories = categories,
            )
        }
        updateScheduledStarts()
    }

    private fun updateScheduledStarts() {
        val id = _state.value.preview?.id
        _state.update { it.copy(scheduledStarts = if (id == null) emptySet() else scheduledStarts(planned, id)) }
    }

    override fun onProgrammeClick(programme: LiveProgramme) {
        val repo = recordings ?: return
        val preview = _state.value.preview ?: return
        val nowSecs = System.currentTimeMillis() / 1000
        if (programme.stop <= nowSecs) return
        viewModelScope.launch {
            val existing = recordingFor(planned, preview.id, programme)
            if (existing == null) {
                // Format TS refusé par le fournisseur : rien à copier tel quel.
                if (attempt { session.withCore { it.canRecord() } }.getOrDefault(true).not()) {
                    showMessage(UiText(R.string.record_unsupported))
                    return@launch
                }
            }
            val margins = attempt { repo.withStore { it.lastMargins() } }.getOrNull()
                ?: io.github.shweepsi.heystream.core.defaultRecordingMargins()
            // Différé de cette chaîne : son débit est mesuré en continu.
            val bufferRate = if (existing == null) bufferFor(preview.id)?.rate() else null
            if (bufferRate != null) {
                attempt { repo.withStore { it.learnRate(preview.id, bufferRate.first.toULong(), bufferRate.second) } }
            }
            _state.update {
                it.copy(
                    recordDialog = RecordDialogUi(
                        preview.id,
                        preview.name,
                        programme,
                        margins,
                        scheduledId = existing?.id,
                        measuring = existing == null && bufferRate == null,
                    ),
                )
            }
            if (existing == null && bufferRate != null) {
                refreshRecordPreview()
            } else if (existing == null) {
                refreshRecordPreview()
                // Mesure du débit sur l'aperçu de cette chaîne, à l'ouverture du
                // dialogue : 10 s de flux, puis la taille est recalculée.
                val token = ++measureToken
                player.measureLiveRate { rate ->
                    viewModelScope.launch {
                        // La mesure de la chaîne de l'aperçu, retenue (la dernière remplace la précédente).
                        if (rate != null && rate.streamId == preview.id) {
                            attempt { repo.withStore { it.learnRate(rate.streamId, rate.bytes.toULong(), rate.secs) } }
                        }
                        // Dialogue fermé ou rouvert entre-temps : une autre mesure le concerne.
                        if (token != measureToken || _state.value.recordDialog == null) return@launch
                        _state.update { current -> current.copy(recordDialog = current.recordDialog?.copy(measuring = false)) }
                        refreshRecordPreview()
                    }
                }
            }
        }
    }

    private fun request(dialog: RecordDialogUi, adult: Boolean) = ScheduleRequest(
        streamId = dialog.channelId,
        channel = dialog.channel,
        isAdult = adult,
        programme = GuideProgramme(dialog.programme.title, null, dialog.programme.start, dialog.programme.stop),
        margins = dialog.margins,
        // « Enregistrer » pendant le différé : le début déjà passé vient du tampon.
        bufferedFrom = bufferFor(dialog.channelId)?.window()?.oldest?.toLong(),
    )

    /** Différé encore en vie de la chaîne [channelId]. */
    private fun bufferFor(channelId: Long): LiveTimeshift? =
        timeshift?.current?.value?.takeIf { it.streamId == channelId && it.alive }

    private suspend fun isAdult(channelId: Long): Boolean =
        attempt { session.withCore { it.isAdult(Section.LIVE, channelId) } }.getOrDefault(true)

    private var previewJob: Job? = null

    /** Mesure du débit en cours : seule la dernière demandée met à jour le dialogue. */
    private var measureToken = 0

    /** Taille, fusion et place manquante, recalculées à chaque changement de marge. */
    private fun refreshRecordPreview() {
        val repo = recordings ?: return
        val dialog = _state.value.recordDialog ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            attempt { repo.ensureDirectory() }
            val adult = isAdult(dialog.channelId)
            val bufferBytes = bufferFor(dialog.channelId)?.bytes() ?: 0L
            val result = attempt { repo.withStore { it.preview(request(dialog, adult), bufferBytes.toULong()) } }
            _state.update { current ->
                val shown = current.recordDialog ?: return@update current
                if (shown.margins != dialog.margins) return@update current
                current.copy(
                    recordDialog = shown.copy(
                        preview = result.getOrNull(),
                        error = result.exceptionOrNull()?.toUiText(),
                    ),
                )
            }
        }
    }

    fun onAdjustMargin(before: Boolean, delta: Long) {
        _state.update { current ->
            val dialog = current.recordDialog ?: return@update current
            current.copy(recordDialog = dialog.copy(margins = dialog.margins.adjusted(before, delta), preview = null))
        }
        refreshRecordPreview()
    }

    /** « Enregistrer » ou « Programmer quand même ». */
    fun onConfirmRecord() {
        val repo = recordings ?: return
        val dialog = _state.value.recordDialog ?: return
        if (dialog.busy) return
        _state.update { it.copy(recordDialog = dialog.copy(busy = true)) }
        viewModelScope.launch {
            val adult = isAdult(dialog.channelId)
            val result = attempt {
                repo.ensureDirectory()
                repo.withStore { it.schedule(request(dialog, adult)) }
            }
            result.onSuccess {
                _state.update { it.copy(recordDialog = null, alarmPrompt = !canScheduleExact()) }
                repo.changed()
                showMessage(UiText(R.string.record_scheduled, listOf(dialog.programme.title)))
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(recordDialog = current.recordDialog?.copy(busy = false, error = error.toUiText()))
                }
            }
        }
    }

    /** « Libérer de la place » : enregistrements proposés, déjà vus d'abord. */
    fun onFreeSpace() {
        val repo = recordings ?: return
        val dialog = _state.value.recordDialog ?: return
        val missing = dialog.preview?.missingBytes ?: return
        viewModelScope.launch {
            val unlocked = attempt { session.withCore { it.isParentalUnlocked() } }.getOrDefault(false)
            val rows = attempt { repo.withStore { it.cleanupCandidates(missing, unlocked) } }.getOrDefault(emptyList()).map { it.toRow() }
            _state.update { current -> current.copy(recordDialog = current.recordDialog?.copy(cleanup = rows)) }
        }
    }

    /** Supprime les enregistrements proposés, puis programme. */
    fun onConfirmCleanup() {
        val repo = recordings ?: return
        val rows = _state.value.recordDialog?.cleanup ?: return
        viewModelScope.launch {
            rows.forEach { row -> attempt { repo.withStore { it.delete(row.id) } } }
            repo.changed()
            _state.update { current -> current.copy(recordDialog = current.recordDialog?.copy(cleanup = null)) }
            onConfirmRecord()
        }
    }

    fun onCancelCleanup() {
        _state.update { current -> current.copy(recordDialog = current.recordDialog?.copy(cleanup = null)) }
    }

    /** Programme déjà programmé : l'enregistrement est annulé (ou arrêté s'il est en cours). */
    fun onCancelRecording() {
        val repo = recordings ?: return
        val id = _state.value.recordDialog?.scheduledId ?: _state.value.recordingOptions?.id ?: return
        viewModelScope.launch {
            if (repo.active.value?.id == id) {
                repo.active.value?.control?.stop()
            } else {
                attempt { repo.withStore { it.cancel(id) } }
            }
            repo.changed()
            _state.update { it.copy(recordDialog = null, recordingOptions = null) }
            showMessage(UiText(R.string.record_cancelled))
        }
    }

    fun onDismissRecordDialog() {
        previewJob?.cancel()
        _state.update { it.copy(recordDialog = null) }
    }

    fun onAlarmPromptDone() {
        _state.update { it.copy(alarmPrompt = false) }
    }

    /** Enregistrement à ouvrir dans le lecteur (consommé par l'écran). */
    private val _openRecording = MutableStateFlow<Long?>(null)
    val openRecording: StateFlow<Long?> = _openRecording.asStateFlow()

    fun onRecordingOpened() {
        _openRecording.value = null
    }

    override fun onRecordingClick(row: RecordingRow) {
        if (row.playable) {
            // Le lecteur des enregistrements remplace l'aperçu : le flux est fermé,
            // puis le lecteur lui appartient.
            onLeave()
            handedOver = true
            _openRecording.value = row.id
        } else {
            onRecordingOptions(row)
        }
    }

    override fun onRecordingOptions(row: RecordingRow) {
        _state.update { it.copy(recordingOptions = row) }
    }

    fun onDismissRecordingOptions() {
        _state.update { it.copy(recordingOptions = null) }
    }

    fun onDeleteRecording() {
        val repo = recordings ?: return
        val row = _state.value.recordingOptions ?: return
        viewModelScope.launch {
            attempt { repo.withStore { it.delete(row.id) } }.onFailure { showMessage(it.toUiText()) }
            repo.changed()
            _state.update { it.copy(recordingOptions = null) }
        }
    }

    override fun onStopRecording() {
        recordings?.active?.value?.control?.stop()
    }

    private companion object {
        /** Aperçu depuis le fichier en cours : environ 5 s avant la fin (à 3 Go/h). */
        const val LIVE_EDGE_BYTES = 4L * 1024 * 1024

        /** Favoris lus pour le cœur en bout de ligne et leur nombre. */
        const val FAVORITES_LIMIT = 2_000
        const val VISIBLE_DEBOUNCE_MS = 150L
        const val OVERLAY_MS = 4_000L

        /** Différé : barre relue, premières données attendues au plus. */
        const val TIMESHIFT_TICK_MS = 500L
        const val TIMESHIFT_FIRST_DATA_MS = 15_000L
        const val TIMESHIFT_POLL_MS = 100L
        const val MESSAGE_MS = 2_500L

        /** Le programme en cours change avec l'heure : relu chaque minute. */
        fun minuteTicks(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(60_000)
            }
        }
    }
}

fun CatalogItem.toPreview() = LivePreview(id = id, name = name, number = number, logo = image)

/** Comme `runCatching`, sans avaler l'annulation de la coroutine. */
private suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

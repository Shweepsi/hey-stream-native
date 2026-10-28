//! Liaisons UniFFI du cœur hey-stream.
//!
//! Une seule classe, [`HeyStreamCore`], par abonnement. Toutes ses méthodes
//! sont **bloquantes** : les interfaces les appellent hors du fil principal
//! (`Dispatchers.IO` en Kotlin, une `Task` détachée en Swift). Les lectures
//! du cache répondent en millisecondes ; seules les méthodes réseau
//! (`authenticate`, `refresh*`, `now_next`, `movie_info`, `series_info`)
//! peuvent durer quelques secondes, et le cache reste lisible pendant ce
//! temps : le téléchargement se fait hors du verrou, et l'enregistrement
//! d'une section passe par une seconde connexion SQLite.

use std::ops::{Deref, DerefMut};
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use xtream_core as xc;

uniffi::setup_scaffolding!();

// --- Erreurs -----------------------------------------------------------------

/// Erreurs remontées aux interfaces, qui les traduisent. Aucune ne contient
/// d'URL ni d'identifiant.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum HeyStreamError {
    #[error("URL du serveur invalide")]
    InvalidServerUrl,
    #[error("identifiants manquants")]
    MissingCredentials,
    #[error("identifiants refusés")]
    Unauthorized,
    #[error("abonnement expiré ou désactivé")]
    AccountInactive,
    #[error("API Xtream introuvable")]
    NotFound,
    #[error("erreur du serveur")]
    Server { status: u16 },
    #[error("réponse inattendue")]
    UnexpectedStatus { status: u16 },
    #[error("réponse illisible")]
    InvalidResponse,
    #[error("erreur réseau")]
    Network,
    #[error("erreur du cache local")]
    Storage,
    #[error("catégorie verrouillée")]
    ParentalLock,
    #[error("le code doit comporter 4 chiffres")]
    InvalidPin,
    #[error("code incorrect")]
    WrongPin,
    #[error("trop de codes incorrects")]
    TooManyAttempts { retry_in_secs: i64 },
    /// Après `sign_out` : l'objet ne sert plus, en créer un nouveau.
    #[error("abonnement déconnecté")]
    SignedOut,
}

impl From<xc::Error> for HeyStreamError {
    fn from(error: xc::Error) -> Self {
        use xc::Error as E;
        match error {
            E::InvalidServerUrl => Self::InvalidServerUrl,
            E::MissingCredentials => Self::MissingCredentials,
            E::Unauthorized => Self::Unauthorized,
            E::AccountInactive => Self::AccountInactive,
            E::NotFound => Self::NotFound,
            E::Server(status) => Self::Server { status },
            E::UnexpectedStatus(status) => Self::UnexpectedStatus { status },
            E::InvalidResponse => Self::InvalidResponse,
            // Le détail réseau contient l'URL, donc les identifiants : on ne
            // le transmet pas.
            E::Network(_) => Self::Network,
            E::Storage(_) => Self::Storage,
            E::ParentalLock => Self::ParentalLock,
            E::InvalidPin => Self::InvalidPin,
            E::WrongPin => Self::WrongPin,
            E::TooManyAttempts { retry_in_secs } => Self::TooManyAttempts { retry_in_secs },
        }
    }
}

type Result<T> = std::result::Result<T, HeyStreamError>;

// --- Types exposés -------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Section {
    Live,
    Movies,
    Series,
}

impl From<Section> for xc::Section {
    fn from(s: Section) -> Self {
        match s {
            Section::Live => xc::Section::Live,
            Section::Movies => xc::Section::Movies,
            Section::Series => xc::Section::Series,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PlaybackKind {
    Movie,
    Episode,
}

impl From<PlaybackKind> for xc::PlaybackKind {
    fn from(k: PlaybackKind) -> Self {
        match k {
            PlaybackKind::Movie => xc::PlaybackKind::Movie,
            PlaybackKind::Episode => xc::PlaybackKind::Episode,
        }
    }
}

impl From<xc::PlaybackKind> for PlaybackKind {
    fn from(k: xc::PlaybackKind) -> Self {
        match k {
            xc::PlaybackKind::Movie => PlaybackKind::Movie,
            xc::PlaybackKind::Episode => PlaybackKind::Episode,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AccountInfo {
    /// Expiration (secondes Unix), absente pour un compte illimité.
    pub expires_at: Option<i64>,
    pub max_connections: Option<i64>,
    pub active_connections: Option<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CategoryEntry {
    pub id: String,
    pub name: String,
    pub is_adult: bool,
    pub locked: bool,
    pub hidden: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CatalogItem {
    pub section: Section,
    pub id: i64,
    pub name: String,
    pub image: Option<String>,
    pub category_id: Option<String>,
    pub extension: Option<String>,
    pub epg_channel_id: Option<String>,
    pub rating: Option<String>,
    pub plot: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SectionRefresh {
    pub section: Section,
    /// Faux si la section était à jour (moins de 24 h).
    pub refreshed: bool,
    /// Vrai si le fournisseur a renvoyé une liste vide et que le cache a été
    /// gardé.
    pub kept_cache: bool,
    /// Vrai si cette section n'a pas pu être téléchargée (cache intact).
    pub failed: bool,
    pub items: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Programme {
    pub title: String,
    pub description: Option<String>,
    pub start: i64,
    pub stop: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct NowNext {
    pub now: Option<Programme>,
    pub next: Option<Programme>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Resume {
    pub kind: PlaybackKind,
    pub id: i64,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub title: String,
    pub image: Option<String>,
    pub extension: Option<String>,
    pub series_id: Option<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ResumeEntry {
    pub resume: Resume,
    pub updated_at: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MovieInfo {
    pub name: Option<String>,
    pub plot: Option<String>,
    pub year: Option<String>,
    pub duration_secs: Option<i64>,
    pub rating: Option<String>,
    pub image: Option<String>,
    pub extension: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Episode {
    pub id: i64,
    pub season: i64,
    pub number: Option<i64>,
    pub title: Option<String>,
    pub extension: Option<String>,
    pub plot: Option<String>,
    pub image: Option<String>,
    pub duration_secs: Option<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Season {
    pub number: i64,
    pub name: Option<String>,
    pub episodes: Vec<Episode>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SeriesInfo {
    pub name: Option<String>,
    pub plot: Option<String>,
    pub year: Option<String>,
    pub rating: Option<String>,
    pub cover: Option<String>,
    pub seasons: Vec<Season>,
}

impl From<xc::CachedCategory> for CategoryEntry {
    fn from(c: xc::CachedCategory) -> Self {
        Self {
            id: c.id,
            name: c.name,
            is_adult: c.is_adult,
            locked: c.locked,
            hidden: c.hidden,
        }
    }
}

impl From<xc::CatalogItem> for CatalogItem {
    fn from(i: xc::CatalogItem) -> Self {
        Self {
            section: match i.section {
                xc::Section::Live => Section::Live,
                xc::Section::Movies => Section::Movies,
                xc::Section::Series => Section::Series,
            },
            id: i.id,
            name: i.name,
            image: i.image,
            category_id: i.category_id,
            extension: i.extension,
            epg_channel_id: i.epg_channel_id,
            rating: i.rating,
            plot: i.plot,
        }
    }
}

impl From<xc::Programme> for Programme {
    fn from(p: xc::Programme) -> Self {
        Self {
            title: p.title,
            description: p.description,
            start: p.start,
            stop: p.stop,
        }
    }
}

impl From<Resume> for xc::Resume {
    fn from(r: Resume) -> Self {
        Self {
            kind: r.kind.into(),
            id: r.id,
            position_ms: r.position_ms,
            duration_ms: r.duration_ms,
            title: r.title,
            image: r.image,
            extension: r.extension,
            series_id: r.series_id,
        }
    }
}

impl From<xc::Resume> for Resume {
    fn from(r: xc::Resume) -> Self {
        Self {
            kind: r.kind.into(),
            id: r.id,
            position_ms: r.position_ms,
            duration_ms: r.duration_ms,
            title: r.title,
            image: r.image,
            extension: r.extension,
            series_id: r.series_id,
        }
    }
}

impl From<xc::Episode> for Episode {
    fn from(e: xc::Episode) -> Self {
        Self {
            id: e.id,
            season: e.season,
            number: e.number,
            title: e.title,
            extension: e.extension,
            plot: e.plot,
            image: e.image,
            duration_secs: e.duration_secs,
        }
    }
}

impl From<xc::SeriesInfo> for SeriesInfo {
    fn from(s: xc::SeriesInfo) -> Self {
        Self {
            name: s.name,
            plot: s.plot,
            year: s.year,
            rating: s.rating,
            cover: s.cover,
            seasons: s
                .seasons
                .into_iter()
                .map(|season| Season {
                    number: season.number,
                    name: season.name,
                    episodes: season.episodes.into_iter().map(Into::into).collect(),
                })
                .collect(),
        }
    }
}

fn items(list: Vec<xc::CatalogItem>) -> Vec<CatalogItem> {
    list.into_iter().map(Into::into).collect()
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

// --- Objet principal -----------------------------------------------------------

/// Un abonnement Xtream et son cache local.
///
/// Verrous, toujours pris dans cet ordre : `refresh_gate`, `writer`, `store`.
#[derive(uniffi::Object)]
pub struct HeyStreamCore {
    client: xc::XtreamClient,
    account: xc::AccountKey,
    /// Lectures du cache et petites écritures (favoris, reprise, EPG…).
    store: Mutex<Store>,
    /// Seconde connexion au même fichier, réservée à l'enregistrement des
    /// sections : des dizaines de milliers de lignes écrites sans bloquer les
    /// lectures (SQLite en WAL).
    writer: Mutex<xc::Catalog>,
    runtime: tokio::runtime::Runtime,
    /// Un seul rafraîchissement à la fois : deux appels simultanés (lancement
    /// et bouton manuel) ne téléchargent pas deux fois le catalogue.
    refresh_gate: Mutex<()>,
    /// Dernière tentative de téléchargement de chaque section, réussie ou non.
    attempts: Mutex<[Option<i64>; 3]>,
    /// Dernière fiche série chargée, pour l'épisode suivant sans requête.
    last_series: Mutex<Option<(i64, xc::SeriesInfo)>>,
}

/// Cache et lien avec l'abonnement, sous le même verrou.
struct Store {
    catalog: xc::Catalog,
    account: AccountState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AccountState {
    /// Le cache est celui de cet abonnement.
    Bound,
    /// Le cache est celui d'un autre identifiant ou d'une autre adresse : il
    /// n'est ni effacé ni rafraîchi avant qu'`authenticate` réussisse.
    Pending,
    /// Après `sign_out`.
    SignedOut,
}

impl Deref for Store {
    type Target = xc::Catalog;
    fn deref(&self) -> &xc::Catalog {
        &self.catalog
    }
}

impl DerefMut for Store {
    fn deref_mut(&mut self) -> &mut xc::Catalog {
        &mut self.catalog
    }
}

/// Une section tentée il y a moins d'une minute (réussite ou échec) n'est
/// pas retéléchargée, même si le rafraîchissement est forcé.
const MIN_ATTEMPT_INTERVAL_SECS: i64 = 60;

#[uniffi::export]
impl HeyStreamCore {
    /// Ouvre l'abonnement ; le cache est stocké dans `data_dir/catalog.db`.
    /// Ne fait aucune requête réseau. Si le cache est celui d'un autre
    /// identifiant ou d'une autre adresse, il n'est effacé (autre
    /// identifiant) ou rechargé (autre adresse) qu'après un `authenticate`
    /// réussi : une faute de frappe dans le formulaire n'efface rien.
    #[uniffi::constructor]
    pub fn new(
        server_url: String,
        username: String,
        password: String,
        data_dir: String,
    ) -> Result<std::sync::Arc<Self>> {
        let client = xc::XtreamClient::new(&xc::Credentials {
            server_url,
            username,
            password,
        })?;
        let path = PathBuf::from(data_dir).join("catalog.db");
        let mut catalog = xc::Catalog::open(&path)?;
        let account = client.account_key();
        let state = match catalog.account_match(&account)? {
            xc::AccountMatch::Same => AccountState::Bound,
            // Cache neuf, ou restes d'une déconnexion : ils ne sont à personne.
            xc::AccountMatch::Unbound => {
                catalog.bind_account(&account)?;
                AccountState::Bound
            }
            xc::AccountMatch::OtherServer | xc::AccountMatch::OtherUser => AccountState::Pending,
        };
        let writer = xc::Catalog::open(&path)?;
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|_| HeyStreamError::Storage)?;
        Ok(std::sync::Arc::new(Self {
            client,
            account,
            store: Mutex::new(Store {
                catalog,
                account: state,
            }),
            writer: Mutex::new(writer),
            runtime,
            refresh_gate: Mutex::new(()),
            attempts: Mutex::new([None; 3]),
            last_series: Mutex::new(None),
        }))
    }

    // Réseau -----------------------------------------------------------------

    /// Vérifie les identifiants (formulaire de connexion, infos abonnement).
    /// Réussi, il lie le cache à cet abonnement.
    pub fn authenticate(&self) -> Result<AccountInfo> {
        self.check_signed_in()?;
        let account = self.runtime.block_on(self.client.authenticate())?;
        self.bind_if_pending()?;
        Ok(AccountInfo {
            expires_at: account.expires_at,
            max_connections: account.max_connections,
            active_connections: account.active_connections,
        })
    }

    /// Rafraîchit les sections de plus de 24 h (toutes si `force`). Une
    /// section en échec n'empêche pas les autres (`failed`) ; une erreur
    /// n'est renvoyée que si toutes les sections à rafraîchir ont échoué.
    pub fn refresh(&self, force: bool) -> Result<Vec<SectionRefresh>> {
        let _gate = self
            .refresh_gate
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // Cache d'un autre abonnement : identifiants vérifiés avant de le
        // remplacer.
        if self.lock()?.account == AccountState::Pending {
            self.authenticate()?;
        }
        let mut results = Vec::new();
        let mut first_error = None;
        let (mut attempted, mut succeeded) = (0, 0);
        for section in [Section::Live, Section::Movies, Section::Series] {
            let mut entry = SectionRefresh {
                section,
                refreshed: false,
                kept_cache: false,
                failed: false,
                items: 0,
            };
            if self.refresh_due(section, force)? {
                attempted += 1;
                self.attempts_lock()[section as usize] = Some(now());
                // Téléchargement hors verrou : le cache reste lisible.
                let fetched = self
                    .runtime
                    .block_on(xc::fetch_section(&self.client, section.into()));
                match fetched
                    .map_err(HeyStreamError::from)
                    .and_then(|data| self.store_section(&data))
                {
                    Ok(xc::RefreshOutcome::Updated { items, .. }) => {
                        succeeded += 1;
                        entry.refreshed = true;
                        entry.items = items as u64;
                    }
                    Ok(xc::RefreshOutcome::KeptCache) => {
                        succeeded += 1;
                        entry.kept_cache = true;
                    }
                    Err(error) => {
                        entry.failed = true;
                        first_error.get_or_insert(error);
                    }
                }
            }
            results.push(entry);
        }
        match first_error {
            Some(error) if attempted > 0 && succeeded == 0 => Err(error),
            _ => Ok(results),
        }
    }

    /// Programme en cours et suivant d'une chaîne (cache, puis fournisseur au
    /// plus une fois toutes les 15 min).
    pub fn now_next(&self, stream_id: i64) -> Result<NowNext> {
        let t = now();
        // La demande est réservée sous le verrou : un appel simultané pour la
        // même chaîne lit le cache sans envoyer de seconde requête. En cas
        // d'erreur réseau, le cache reste tel quel.
        let claimed = self.lock()?.claim_epg_fetch(stream_id, t)?;
        if claimed && let Ok(programmes) = self.runtime.block_on(self.client.short_epg(stream_id)) {
            self.lock()?.store_epg(stream_id, &programmes, t)?;
        }
        let nn = self.lock()?.now_next(stream_id, t)?;
        Ok(NowNext {
            now: nn.now.map(Into::into),
            next: nn.next.map(Into::into),
        })
    }

    pub fn movie_info(&self, stream_id: i64) -> Result<MovieInfo> {
        let info = self.runtime.block_on(self.client.movie_info(stream_id))?;
        Ok(MovieInfo {
            name: info.name,
            plot: info.plot,
            year: info.year,
            duration_secs: info.duration_secs,
            rating: info.rating,
            image: info.image,
            extension: info.extension,
        })
    }

    pub fn series_info(&self, series_id: i64) -> Result<SeriesInfo> {
        Ok(self.load_series(series_id)?.into())
    }

    /// Épisode qui suit `episode_id` (lecture automatique), d'après la fiche
    /// déjà chargée par `series_info`, sinon rechargée.
    pub fn next_episode(&self, series_id: i64, episode_id: i64) -> Result<Option<Episode>> {
        let cached = self
            .last_series_lock()
            .as_ref()
            .filter(|(id, _)| *id == series_id)
            .map(|(_, info)| info.next_episode(episode_id).cloned());
        let next = match cached {
            Some(next) => next,
            None => self
                .load_series(series_id)?
                .next_episode(episode_id)
                .cloned(),
        };
        Ok(next.map(Into::into))
    }

    /// Déconnexion : efface catalogue, favoris, reprise, code PIN et EPG de
    /// cet abonnement. Un enregistrement en cours se termine d'abord ; un
    /// téléchargement en cours ne sera pas enregistré. L'objet ne sert plus
    /// ensuite (`SignedOut`).
    pub fn sign_out(&self) -> Result<()> {
        let _writer = self.writer_lock();
        let mut store = self.lock_any();
        store.account = AccountState::SignedOut;
        *self.last_series_lock() = None;
        Ok(store.clear_all()?)
    }

    /// URL de lecture, à donner au lecteur uniquement (jamais journalisée).
    pub fn stream_url(&self, kind: StreamKind, id: i64, extension: Option<String>) -> String {
        self.client
            .stream_url(kind.into(), id, extension.as_deref())
            .to_string()
    }

    // Catalogue --------------------------------------------------------------

    pub fn categories(&self, section: Section) -> Result<Vec<CategoryEntry>> {
        Ok(self
            .lock()?
            .categories(section.into())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn all_categories(&self, section: Section) -> Result<Vec<CategoryEntry>> {
        Ok(self
            .lock()?
            .all_categories(section.into())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn count(&self, section: Section, category_id: Option<String>) -> Result<u64> {
        Ok(self.lock()?.count(section.into(), category_id.as_deref())? as u64)
    }

    pub fn items(
        &self,
        section: Section,
        category_id: Option<String>,
        offset: u32,
        limit: u32,
    ) -> Result<Vec<CatalogItem>> {
        Ok(items(self.lock()?.items(
            section.into(),
            category_id.as_deref(),
            offset as usize,
            limit as usize,
        )?))
    }

    pub fn item(&self, section: Section, id: i64) -> Result<Option<CatalogItem>> {
        Ok(self.lock()?.item(section.into(), id)?.map(Into::into))
    }

    pub fn search(&self, section: Section, query: String, limit: u32) -> Result<Vec<CatalogItem>> {
        Ok(items(self.lock()?.search(
            section.into(),
            &query,
            limit as usize,
        )?))
    }

    // Favoris, récents, reprise ---------------------------------------------

    pub fn toggle_favorite(&self, section: Section, id: i64) -> Result<bool> {
        Ok(self.lock()?.toggle_favorite(section.into(), id, now())?)
    }

    pub fn is_favorite(&self, section: Section, id: i64) -> Result<bool> {
        Ok(self.lock()?.is_favorite(section.into(), id)?)
    }

    pub fn favorites(&self, section: Section, offset: u32, limit: u32) -> Result<Vec<CatalogItem>> {
        Ok(items(self.lock()?.favorites(
            section.into(),
            offset as usize,
            limit as usize,
        )?))
    }

    pub fn mark_watched(&self, section: Section, id: i64) -> Result<()> {
        Ok(self.lock()?.mark_watched(section.into(), id, now())?)
    }

    pub fn recents(&self, section: Section, limit: u32) -> Result<Vec<CatalogItem>> {
        Ok(items(self.lock()?.recents(section.into(), limit as usize)?))
    }

    pub fn save_progress(&self, resume: Resume) -> Result<()> {
        Ok(self.lock()?.save_progress(&resume.into(), now())?)
    }

    pub fn progress(&self, kind: PlaybackKind, id: i64) -> Result<Option<i64>> {
        Ok(self.lock()?.progress(kind.into(), id)?)
    }

    pub fn clear_progress(&self, kind: PlaybackKind, id: i64) -> Result<()> {
        Ok(self.lock()?.clear_progress(kind.into(), id)?)
    }

    pub fn continue_watching(&self, limit: u32) -> Result<Vec<ResumeEntry>> {
        Ok(self
            .lock()?
            .continue_watching(limit as usize)?
            .into_iter()
            .map(|e| ResumeEntry {
                resume: e.resume.into(),
                updated_at: e.updated_at,
            })
            .collect())
    }

    // Contrôle parental ------------------------------------------------------

    pub fn has_pin(&self) -> Result<bool> {
        Ok(self.lock()?.has_pin()?)
    }

    pub fn set_pin(&self, new_pin: String, current: Option<String>) -> Result<()> {
        Ok(self.lock()?.set_pin(&new_pin, current.as_deref(), now())?)
    }

    pub fn unlock_parental(&self, pin: String) -> Result<()> {
        Ok(self.lock()?.unlock_parental(&pin, now())?)
    }

    pub fn lock_parental(&self) {
        self.lock_any().lock_parental();
    }

    pub fn is_parental_unlocked(&self) -> bool {
        self.lock_any().is_parental_unlocked()
    }

    pub fn set_category_hidden(&self, section: Section, id: String, hidden: bool) -> Result<()> {
        Ok(self
            .lock()?
            .set_category_hidden(section.into(), &id, hidden)?)
    }
}

impl HeyStreamCore {
    fn refresh_due(&self, section: Section, force: bool) -> Result<bool> {
        let t = now();
        if !force && !self.lock()?.needs_refresh(section.into(), t)? {
            return Ok(false);
        }
        // Tentée il y a moins d'une minute, par exemple par un appel qui
        // attendait derrière le même verrou : pas de nouveau téléchargement,
        // même après un échec.
        Ok(self.attempts_lock()[section as usize]
            .is_none_or(|at| t < at || t - at >= MIN_ATTEMPT_INTERVAL_SECS))
    }

    /// Enregistre une section sur la connexion d'écriture, sauf après une
    /// déconnexion survenue pendant le téléchargement.
    fn store_section(&self, data: &xc::SectionData) -> Result<xc::RefreshOutcome> {
        let mut writer = self.writer_lock();
        self.check_signed_in()?;
        Ok(writer.store_section(data, now())?)
    }

    /// Lie le cache à cet abonnement après une authentification réussie :
    /// cache d'un autre identifiant effacé, d'une autre adresse à recharger.
    fn bind_if_pending(&self) -> Result<()> {
        if self.lock()?.account != AccountState::Pending {
            return Ok(());
        }
        let _writer = self.writer_lock();
        let mut store = self.lock()?;
        if store.account == AccountState::Pending {
            store.bind_account(&self.account)?;
            store.account = AccountState::Bound;
            *self.attempts_lock() = [None; 3];
        }
        Ok(())
    }

    fn load_series(&self, series_id: i64) -> Result<xc::SeriesInfo> {
        let info = self.runtime.block_on(self.client.series_info(series_id))?;
        *self.last_series_lock() = Some((series_id, info.clone()));
        Ok(info)
    }

    fn last_series_lock(&self) -> MutexGuard<'_, Option<(i64, xc::SeriesInfo)>> {
        self.last_series
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn attempts_lock(&self) -> MutexGuard<'_, [Option<i64>; 3]> {
        self.attempts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn writer_lock(&self) -> MutexGuard<'_, xc::Catalog> {
        self.writer
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Le cache, sauf après `sign_out`.
    fn lock(&self) -> Result<MutexGuard<'_, Store>> {
        let store = self.lock_any();
        if store.account == AccountState::SignedOut {
            return Err(HeyStreamError::SignedOut);
        }
        Ok(store)
    }

    fn check_signed_in(&self) -> Result<()> {
        self.lock().map(drop)
    }

    /// Un panic pendant une écriture ne doit pas rendre le cache inutilisable :
    /// SQLite garantit déjà la cohérence des transactions.
    fn lock_any(&self) -> MutexGuard<'_, Store> {
        self.store
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StreamKind {
    Live,
    Movie,
    Series,
}

impl From<StreamKind> for xc::StreamKind {
    fn from(k: StreamKind) -> Self {
        match k {
            StreamKind::Live => xc::StreamKind::Live,
            StreamKind::Movie => xc::StreamKind::Movie,
            StreamKind::Series => xc::StreamKind::Series,
        }
    }
}

/// Version de la bibliothèque, pour l'écran « À propos ».
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

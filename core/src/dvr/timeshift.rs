//! Différé sur le direct (`docs/DECISIONS.md`, « Différé sur le direct »).
//!
//! Un [`Tuner`] tient l'**unique connexion** à une chaîne et copie le flux,
//! tel quel (.ts), dans un tampon de fichiers d'environ une minute. Le
//! lecteur lit toujours ces fichiers, même au direct : revenir en arrière ne
//! demande aucune seconde connexion. Un enregistrement de la même chaîne
//! suit le même tampon ([`Tuner::attach`]), depuis un moment déjà passé si
//! le tampon le contient.
//!
//! - **Index** : heure de réception ↔ octet, chaque seconde, pour des sauts
//!   précis ([`Tuner::locate`]).
//! - **Séries** : après une reconnexion, le flux repart avec une autre
//!   horloge ; une nouvelle série commence, que le lecteur ouvre à part. Le
//!   temps sans données entre deux séries est un trou ([`Window::gaps`]).
//! - **Connexion** : un échec à l'ouverture finit tout de suite
//!   ([`TuneEnd::OpenFailed`], l'interface l'affiche) ; une coupure en
//!   cours est réessayée en arrière-plan, comme l'enregistrement.
//! - **Place** : 1 h au plus, et le tampon cède toujours la place : il
//!   efface son plus ancien dès que la place libre passe sous la réserve
//!   des enregistrements plus une marge, donc avant qu'un enregistrement
//!   n'atteigne la réserve.
//! - **Fin** : la copie s'arrête quand plus personne ne regarde ni
//!   n'enregistre ; le dossier du tampon est effacé quand le dernier
//!   [`Tuner`] disparaît. [`purge_timeshift`] efface au lancement les
//!   tampons laissés par un arrêt brutal.
//!
//! L'URL du flux contient les identifiants : elle n'est ni gardée ni
//! journalisée.

use std::collections::{BTreeMap, VecDeque};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use tokio::sync::Notify;

use super::capture::{
    CaptureControl, CaptureEnd, CaptureJob, CaptureOptions, CaptureReport, SpaceProbe, now,
};
use super::space::volume_space;
use crate::client::{XtreamClient, http_builder};
use crate::error::{Error, Result};
use crate::url::StreamKind;

/// Taille d'un paquet TS : les octets de l'index sont alignés dessus.
const TS_PACKET: u64 = 188;
/// Dossier des tampons, dans celui des enregistrements.
const TIMESHIFT_DIR: &str = ".timeshift";
/// Morceau lu à la fois par un enregistrement qui suit le tampon.
const FOLLOW_CHUNK: usize = 256 * 1024;

/// Réglages du différé. Les valeurs par défaut sont celles de l'app.
#[derive(Clone)]
pub struct TunerOptions {
    /// Attente avant le premier nouvel essai après une coupure, doublée à
    /// chaque échec.
    pub first_retry: Duration,
    /// Attente maximale entre deux essais.
    pub max_retry: Duration,
    /// Sans données pendant ce temps, la connexion est refaite.
    pub stall: Duration,
    /// Une connexion qui a fourni des données pendant ce temps ramène
    /// l'attente suivante à `first_retry`.
    pub healthy_after: Duration,
    /// Durée d'un fichier du tampon, en secondes.
    pub segment_secs: f64,
    /// Un point d'index tous les tant de secondes.
    pub mark_secs: f64,
    /// Profondeur maximale du tampon, en secondes.
    pub max_secs: f64,
    /// Le tampon s'efface quand la place libre passe sous la réserve des
    /// enregistrements plus cette marge.
    pub headroom_bytes: u64,
    /// Place libre vérifiée tous les tant d'octets écrits (et à chaque
    /// nouveau fichier).
    pub space_check_bytes: u64,
    pub probe: SpaceProbe,
}

impl Default for TunerOptions {
    fn default() -> Self {
        Self {
            first_retry: Duration::from_secs(2),
            max_retry: Duration::from_secs(60),
            stall: Duration::from_secs(20),
            healthy_after: Duration::from_secs(30),
            segment_secs: 60.0,
            mark_secs: 1.0,
            max_secs: 3600.0,
            headroom_bytes: 500_000_000,
            space_check_bytes: 16 * 1024 * 1024,
            probe: Arc::new(volume_space),
        }
    }
}

impl std::fmt::Debug for TunerOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TunerOptions")
            .field("segment_secs", &self.segment_secs)
            .field("max_secs", &self.max_secs)
            .finish_non_exhaustive()
    }
}

/// Un fichier du tampon.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    /// Numéro, croissant sans trou pendant toute la copie.
    pub seq: u64,
    /// Série : change à chaque reconnexion (horloge du flux qui repart).
    pub run: u64,
    pub path: PathBuf,
    /// Réception du premier et du dernier octet, secondes Unix.
    pub start: f64,
    pub end: f64,
    pub bytes: u64,
    /// Plus rien ne s'y ajoute. Un fichier complet sans suivant de la même
    /// série termine sa série.
    pub complete: bool,
}

/// Où lire pour un moment du tampon.
#[derive(Debug, Clone, PartialEq)]
pub struct Spot {
    pub seq: u64,
    pub run: u64,
    pub path: PathBuf,
    /// Octet de départ dans le fichier, aligné sur un paquet TS.
    pub offset: u64,
    /// Moment réellement atteint (point d'index), secondes Unix.
    pub at: f64,
}

/// Temps sans données dans le tampon (coupure du flux).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Gap {
    pub from: f64,
    pub to: f64,
}

/// Partie disponible du tampon, pour la barre de temps.
#[derive(Debug, Clone, PartialEq)]
pub struct Window {
    /// Moment le plus ancien encore gardé, et dernier reçu.
    pub oldest: f64,
    pub newest: f64,
    pub bytes: u64,
    /// Coupures à l'intérieur de la fenêtre.
    pub gaps: Vec<Gap>,
    /// Des données arrivent en ce moment (pas de coupure en cours).
    pub receiving: bool,
}

/// Comment la copie du différé s'est arrêtée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TuneEnd {
    /// Plus personne ne regarde ni n'enregistre, ou [`Tuner::stop`].
    Stopped,
    /// La première connexion n'a rien donné : l'interface affiche l'erreur
    /// (et peut réessayer en lecture réseau).
    OpenFailed,
    /// Réserve du disque atteinte et plus rien à effacer dans le tampon.
    OutOfSpace,
    /// Fichier impossible à écrire (volume débranché, droits).
    WriteFailed,
    /// Client réseau impossible à créer.
    ConnectionLost,
}

struct Seg {
    info: Segment,
    /// Octets de la série avant ce fichier (alignement des paquets).
    run_offset: u64,
    /// Points d'index : (heure de réception, octet dans le fichier).
    marks: Vec<(f64, u64)>,
}

struct State {
    segments: VecDeque<Seg>,
    next_seq: u64,
    run: u64,
    /// Le prochain morceau ouvre une nouvelle série.
    new_run: bool,
    viewing: bool,
    stopped: bool,
    /// Enregistrements qui suivent le tampon : fichier le plus ancien dont
    /// chacun a encore besoin (jamais effacé avant).
    followers: BTreeMap<u64, u64>,
    next_follower: u64,
    end: Option<TuneEnd>,
    bytes: u64,
    /// Coupures (début, fin ; fin `None` si en cours). La première est
    /// l'attente des premières données.
    outages: Vec<(f64, Option<f64>)>,
    received: bool,
}

struct Shared {
    /// Heure Unix et instant monotone de la création ([`Tuner::clock`]).
    origin: (f64, Instant),
    stream_id: i64,
    dir: PathBuf,
    options: TunerOptions,
    state: Mutex<State>,
    /// Nouvelles données (ou fin) : réveille les enregistrements qui suivent.
    data: Notify,
    /// Arrêt, spectateur parti, enregistrement fini : réveille la copie.
    control: Notify,
}

impl Drop for Shared {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// Différé d'une chaîne : la copie ([`Self::run`]), la lecture
/// ([`Self::locate`], [`Self::segment`], [`Self::window`]) et les
/// enregistrements qui la suivent ([`Self::follow`]). Se partage entre fils
/// (clone).
#[derive(Clone)]
pub struct Tuner {
    shared: Arc<Shared>,
}

impl std::fmt::Debug for Tuner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Tuner")
            .field("stream_id", &self.shared.stream_id)
            .finish_non_exhaustive()
    }
}

/// Efface les tampons laissés par un arrêt brutal de l'app, dans le dossier
/// des enregistrements `dir`. À appeler au lancement, sans différé en cours.
pub fn purge_timeshift(dir: &Path) {
    let _ = std::fs::remove_dir_all(dir.join(TIMESHIFT_DIR));
}

impl Tuner {
    /// Prépare le différé de la chaîne `stream_id` dans le dossier des
    /// enregistrements `dir` (même disque). Quelqu'un regarde déjà.
    pub fn new(stream_id: i64, dir: &Path, options: TunerOptions) -> Result<Self> {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let name = format!(
            "{}-{}",
            (now() * 1000.0) as u64,
            COUNTER.fetch_add(1, Ordering::Relaxed)
        );
        let dir = dir.join(TIMESHIFT_DIR).join(name);
        std::fs::create_dir_all(&dir).map_err(|_| Error::VolumeMissing)?;
        let created = now();
        Ok(Self {
            shared: Arc::new(Shared {
                origin: (created, Instant::now()),
                stream_id,
                dir,
                options,
                state: Mutex::new(State {
                    segments: VecDeque::new(),
                    next_seq: 0,
                    run: 0,
                    new_run: true,
                    viewing: true,
                    stopped: false,
                    followers: BTreeMap::new(),
                    next_follower: 0,
                    end: None,
                    bytes: 0,
                    outages: vec![(created, None)],
                    received: false,
                }),
                data: Notify::new(),
                control: Notify::new(),
            }),
        })
    }

    pub fn stream_id(&self) -> i64 {
        self.shared.stream_id
    }

    /// Dossier des fichiers du tampon.
    pub fn directory(&self) -> &Path {
        &self.shared.dir
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        // Un fil qui panique au milieu ne laisse qu'un index incomplet.
        self.shared
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    // --- Spectateur --------------------------------------------------------

    /// Quelqu'un regarde (aperçu ou plein écran) ou plus personne : sans
    /// spectateur, le tampon ne garde que ce qu'un enregistrement doit
    /// encore copier, et la copie s'arrête s'il n'y en a pas.
    pub fn set_viewing(&self, viewing: bool) {
        self.lock().viewing = viewing;
        self.shared.control.notify_waiters();
    }

    /// Arrête la copie (autre chaîne, enregistrement d'une autre chaîne) ;
    /// les enregistrements qui la suivaient finissent avec ce qu'ils ont.
    pub fn stop(&self) {
        self.lock().stopped = true;
        self.shared.control.notify_waiters();
        self.shared.data.notify_waiters();
    }

    /// Fin de la copie, si elle est finie.
    pub fn end(&self) -> Option<TuneEnd> {
        self.lock().end
    }

    /// Place prise par le tampon : libre pour les enregistrements (il la
    /// cède).
    pub fn bytes(&self) -> u64 {
        self.lock().bytes
    }

    /// Débit mesuré par le tampon (octets, secondes de données), pour
    /// l'estimation des enregistrements.
    pub fn rate(&self) -> Option<(u64, f64)> {
        let state = self.lock();
        let (bytes, secs) = state.segments.iter().fold((0u64, 0f64), |(b, s), seg| {
            (b + seg.info.bytes, s + (seg.info.end - seg.info.start))
        });
        (secs > 0.0).then_some((bytes, secs))
    }

    /// Partie disponible et trous, `None` avant les premières données.
    pub fn window(&self) -> Option<Window> {
        let state = self.lock();
        let first = state.segments.front()?;
        let last = state.segments.back()?;
        let oldest = first.info.start;
        let gaps = state
            .outages
            .iter()
            .filter_map(|&(from, to)| {
                let to = to?;
                (to > oldest).then_some(Gap {
                    from: from.max(oldest),
                    to,
                })
            })
            .collect();
        Some(Window {
            oldest,
            newest: last.info.end,
            bytes: state.bytes,
            gaps,
            receiving: state.end.is_none() && state.outages.last().is_none_or(|o| o.1.is_some()),
        })
    }

    /// Où lire pour revoir le moment `at` (secondes Unix) : le plus ancien
    /// gardé s'il est plus tôt, le dernier point d'index s'il est plus tard.
    /// Dans un trou, la lecture reprend au retour du flux.
    pub fn locate(&self, at: f64) -> Option<Spot> {
        locate(&self.lock(), at)
    }

    /// Un fichier du tampon, `None` s'il est déjà effacé ou pas encore
    /// commencé.
    pub fn segment(&self, seq: u64) -> Option<Segment> {
        let state = self.lock();
        find(&state, seq).map(|seg| seg.info.clone())
    }

    /// Moment reçu à l'octet `offset` du fichier `seq` (position affichée).
    pub fn time_at(&self, seq: u64, offset: u64) -> Option<f64> {
        let state = self.lock();
        find(&state, seq).map(|seg| time_in(seg, offset))
    }

    /// Heure du tampon (secondes Unix) : suit une horloge monotone depuis la
    /// création, pour qu'un réglage de l'heure du système ne casse ni
    /// l'ordre de l'index ni la profondeur.
    pub fn clock(&self) -> f64 {
        let (unix, instant) = self.shared.origin;
        unix + instant.elapsed().as_secs_f64()
    }

    // --- Copie -------------------------------------------------------------

    /// Copie le flux dans le tampon jusqu'à ce que plus personne ne regarde
    /// ni n'enregistre, [`Self::stop`], un échec de la première connexion,
    /// la réserve du disque ou une écriture refusée. Les coupures en cours
    /// de route sont réessayées sans fin.
    pub async fn run(&self, client: &XtreamClient) -> TuneEnd {
        let end = self.copy(client).await;
        {
            let t = self.clock();
            let mut state = self.lock();
            if let Some(last) = state.outages.last_mut()
                && last.1.is_none()
            {
                last.1 = Some(t);
            }
            if let Some(last) = state.segments.back_mut() {
                last.info.complete = true;
            }
            state.end = Some(end);
        }
        self.shared.data.notify_waiters();
        self.erase_if_unused();
        end
    }

    /// Copie finie et plus aucun enregistrement à servir : les fichiers
    /// s'effacent tout de suite (chaîne adulte reverrouillée, place rendue),
    /// sans attendre que le dernier [`Tuner`] disparaisse.
    fn erase_if_unused(&self) {
        {
            let mut state = self.lock();
            if state.end.is_none() || !state.followers.is_empty() {
                return;
            }
            state.bytes = 0;
            state.segments.clear();
        }
        let _ = std::fs::remove_dir_all(&self.shared.dir);
    }

    async fn copy(&self, client: &XtreamClient) -> TuneEnd {
        // Pas de délai global : un différé dure des heures.
        let Ok(http) = http_builder().build() else {
            return TuneEnd::ConnectionLost;
        };
        let url = client.stream_url(StreamKind::Live, self.shared.stream_id, Some("ts"));
        let options = &self.shared.options;
        let mut failures = 0u32;
        loop {
            if let Some(end) = self.idle_end() {
                return end;
            }
            match self.session(&http, url.clone()).await {
                Session::Done(end) => return end,
                Session::Lost { healthy } => {
                    failures = if healthy {
                        1
                    } else {
                        failures.saturating_add(1)
                    };
                }
            }
            {
                let state = self.lock();
                // Rien reçu à l'ouverture : l'interface le dit tout de suite.
                // Un enregistrement qui attend, lui, réessaie.
                if !state.received && state.followers.is_empty() {
                    return TuneEnd::OpenFailed;
                }
            }
            let wait = options
                .first_retry
                .saturating_mul(1 << (failures - 1).min(16))
                .min(options.max_retry);
            let pause = tokio::time::sleep(wait);
            tokio::pin!(pause);
            loop {
                let changed = self.shared.control.notified();
                tokio::pin!(changed);
                changed.as_mut().enable();
                if let Some(end) = self.idle_end() {
                    return end;
                }
                tokio::select! {
                    () = &mut changed => {}
                    () = &mut pause => break,
                }
            }
        }
    }

    /// Fin sans erreur : arrêt demandé, ou plus personne.
    fn idle_end(&self) -> Option<TuneEnd> {
        let state = self.lock();
        (state.stopped || (!state.viewing && state.followers.is_empty()))
            .then_some(TuneEnd::Stopped)
    }

    /// Une connexion au flux, jusqu'à sa perte ou la fin de la copie.
    async fn session(&self, http: &reqwest::Client, url: url::Url) -> Session {
        let options = &self.shared.options;
        self.lock().new_run = true;
        let send = tokio::time::timeout(options.stall, http.get(url).send());
        tokio::pin!(send);
        let response = loop {
            let changed = self.shared.control.notified();
            tokio::pin!(changed);
            changed.as_mut().enable();
            if let Some(end) = self.idle_end() {
                return Session::Done(end);
            }
            tokio::select! {
                () = &mut changed => continue,
                response = &mut send => break response,
            }
        };
        let mut response = match response {
            Ok(Ok(response)) if response.status().is_success() => response,
            _ => return Session::Lost { healthy: false },
        };
        let mut file: Option<File> = None;
        let mut connected_since: Option<f64> = None;
        let mut since_space_check = 0u64;
        // Un seul délai depuis la dernière donnée : les réveils de contrôle
        // (aperçu ↔ plein écran…) ne le repoussent pas.
        let mut stall_at = tokio::time::Instant::now() + options.stall;
        loop {
            let changed = self.shared.control.notified();
            tokio::pin!(changed);
            changed.as_mut().enable();
            if let Some(end) = self.idle_end() {
                return Session::Done(end);
            }
            let chunk = tokio::select! {
                // Lire un morceau peut être abandonné et repris sans rien perdre.
                () = &mut changed => continue,
                chunk = tokio::time::timeout_at(stall_at, response.chunk()) => chunk,
            };
            let chunk = match chunk {
                Ok(Ok(Some(chunk))) => chunk,
                // Fin du flux, erreur ou flux qui cale : nouvelle série.
                _ => {
                    let t = self.clock();
                    let healthy = connected_since
                        .is_some_and(|since| t - since >= options.healthy_after.as_secs_f64());
                    self.lose(t);
                    return Session::Lost { healthy };
                }
            };
            if chunk.is_empty() {
                continue;
            }
            stall_at = tokio::time::Instant::now() + options.stall;
            let t = self.clock();
            if connected_since.is_none() {
                connected_since = Some(t);
                self.connected(t);
            }
            let rotated = match self.write(&mut file, &chunk, t) {
                Ok(rotated) => rotated,
                Err(end) => return Session::Done(end),
            };
            since_space_check += chunk.len() as u64;
            if rotated || since_space_check >= options.space_check_bytes {
                since_space_check = 0;
                if let Err(end) = self.trim() {
                    return Session::Done(end);
                }
            }
            self.shared.data.notify_waiters();
        }
    }

    fn connected(&self, t: f64) {
        let mut state = self.lock();
        state.received = true;
        if let Some(last) = state.outages.last_mut()
            && last.1.is_none()
        {
            last.1 = Some(t);
        }
    }

    fn lose(&self, t: f64) {
        {
            let mut state = self.lock();
            if let Some(last) = state.segments.back_mut() {
                last.info.complete = true;
            }
            if state.outages.last().is_none_or(|o| o.1.is_some()) {
                state.outages.push((t, None));
            }
        }
        self.shared.data.notify_waiters();
    }

    /// Ajoute un morceau au tampon ; vrai si un nouveau fichier a commencé.
    /// Ouverture et écriture se font hors du verrou : un disque lent ne
    /// bloque pas l'interface qui lit l'index.
    fn write(
        &self,
        file: &mut Option<File>,
        chunk: &[u8],
        t: f64,
    ) -> std::result::Result<bool, TuneEnd> {
        let options = &self.shared.options;
        let rotation = {
            let mut state = self.lock();
            let rotate = state.new_run
                || file.is_none()
                || state.segments.back().is_none_or(|seg| {
                    seg.info.complete || t - seg.info.start >= options.segment_secs
                });
            if rotate {
                let new_run = state.new_run;
                let run_offset = match state.segments.back() {
                    Some(last) if !new_run => last.run_offset + last.info.bytes,
                    _ => 0,
                };
                // Le précédent n'est marqué fini qu'avec le suivant en place
                // (plus bas) : un lecteur ne voit jamais « fini sans suivant »
                // au milieu d'une série. Seule la copie avance `next_seq`.
                Some((state.next_seq, new_run, run_offset))
            } else {
                let seg = state.segments.back_mut().expect("fichier en cours");
                let last_mark = seg.marks.last().map_or(f64::MIN, |m| m.0);
                if t - last_mark >= options.mark_secs {
                    // Aligné sur un paquet TS depuis le début de la série.
                    let at = seg.info.bytes;
                    let misalign = (seg.run_offset + at) % TS_PACKET;
                    let first = seg.marks[0].1;
                    seg.marks.push((t, at.saturating_sub(misalign).max(first)));
                }
                None
            }
        };
        if let Some((seq, new_run, run_offset)) = rotation {
            let path = self.shared.dir.join(format!("{seq:08}.ts"));
            let handle = OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(true)
                .open(&path)
                .map_err(|_| TuneEnd::WriteFailed)?;
            *file = Some(handle);
            let mut state = self.lock();
            if let Some(last) = state.segments.back_mut() {
                last.info.complete = true;
            }
            if new_run {
                state.run += 1;
                state.new_run = false;
            }
            let run = state.run;
            // Le fichier précédent a pu finir au milieu d'un paquet : le
            // premier paquet entier commence ici.
            let first = (TS_PACKET - run_offset % TS_PACKET) % TS_PACKET;
            state.segments.push_back(Seg {
                info: Segment {
                    seq,
                    run,
                    path,
                    start: t,
                    end: t,
                    bytes: 0,
                    complete: false,
                },
                run_offset,
                marks: vec![(t, first)],
            });
            state.next_seq += 1;
        }
        let handle = file.as_mut().expect("fichier ouvert");
        handle.write_all(chunk).map_err(|_| TuneEnd::WriteFailed)?;
        let len = chunk.len() as u64;
        let mut state = self.lock();
        let seg = state.segments.back_mut().expect("fichier en cours");
        seg.info.bytes += len;
        seg.info.end = t;
        state.bytes += len;
        Ok(rotation.is_some())
    }

    /// Efface le plus ancien : au-delà de la profondeur, sans spectateur,
    /// ou quand la place libre passe sous la réserve plus la marge. Jamais
    /// un fichier qu'un enregistrement doit encore copier, ni celui en
    /// cours. Réserve atteinte sans rien à effacer : fin de la copie.
    fn trim(&self) -> std::result::Result<(), TuneEnd> {
        let options = &self.shared.options;
        let space = (options.probe)(&self.shared.dir);
        let mut need = space.map_or(0, |s| {
            (s.reserve_bytes() + options.headroom_bytes).saturating_sub(s.free_bytes)
        });
        let mut freed = 0u64;
        let mut removed = Vec::new();
        let mut state = self.lock();
        let pin = state.followers.values().min().copied();
        let newest = state.segments.back().map_or(0.0, |s| s.info.end);
        while state.segments.len() > 1 {
            let oldest = &state.segments[0];
            if pin.is_some_and(|p| p <= oldest.info.seq) {
                break;
            }
            let expired = !state.viewing || newest - oldest.info.end > options.max_secs;
            if !expired && need == 0 {
                break;
            }
            let seg = state.segments.pop_front().expect("plus ancien");
            state.bytes = state.bytes.saturating_sub(seg.info.bytes);
            need = need.saturating_sub(seg.info.bytes);
            freed += seg.info.bytes;
            removed.push(seg.info.path);
        }
        // Coupures plus anciennes que le tampon : inutiles, sauf au bilan
        // d'un enregistrement en cours.
        if state.followers.is_empty()
            && let Some(oldest) = state.segments.front().map(|s| s.info.start)
        {
            let keep_from = state
                .outages
                .iter()
                .position(|o| o.1.is_none_or(|to| to > oldest))
                .unwrap_or(state.outages.len());
            state.outages.drain(..keep_from);
        }
        drop(state);
        // Hors du verrou : effacer sur une clé FAT32 peut être lent.
        for path in removed {
            let _ = std::fs::remove_file(path);
        }
        match space {
            Some(s) if s.free_bytes + freed <= s.reserve_bytes() => Err(TuneEnd::OutOfSpace),
            _ => Ok(()),
        }
    }

    // --- Enregistrement qui suit le tampon ---------------------------------

    /// Inscrit un enregistrement qui suivra le tampon depuis `from` (secondes
    /// Unix, déjà passé si le tampon le contient) : dès cet appel, rien de ce
    /// qu'il doit copier n'est effacé et la copie reste en vie, même sans
    /// spectateur. `None` si la copie est finie ou arrêtée : l'enregistrement
    /// copie alors le flux lui-même ([`super::capture`]).
    ///
    /// Déroulé : `attach`, `Recordings::begin_from` avec
    /// [`Follower::start`], puis [`Follower::copy`].
    pub fn attach(&self, from: i64) -> Option<Follower> {
        let clock = self.clock();
        let mut state = self.lock();
        if state.end.is_some() || state.stopped {
            return None;
        }
        let spot = locate(&state, from as f64);
        let cursor = spot
            .as_ref()
            .map_or((state.next_seq, 0), |s| (s.seq, s.offset));
        let start = spot.map_or(clock, |s| s.at.max(from as f64));
        let id = state.next_follower;
        state.next_follower += 1;
        state.followers.insert(id, cursor.0);
        Some(Follower {
            tuner: self.clone(),
            id,
            cursor,
            start,
        })
    }

    /// Prochain morceau à copier à `cursor` (fichier, octet).
    fn read_next(
        &self,
        reader: &mut Option<(u64, File)>,
        cursor: &(u64, u64),
        buf: &mut [u8],
    ) -> Step {
        let (seq, offset) = *cursor;
        let (complete, has_next, end, path, at) = {
            let state = self.lock();
            match find(&state, seq) {
                Some(seg) => (
                    seg.info.complete,
                    seq + 1 < state.next_seq,
                    state.end,
                    seg.info.path.clone(),
                    time_in(seg, offset),
                ),
                None if seq >= state.next_seq => {
                    return state.end.map_or(Step::Wait, Step::Ended);
                }
                // Effacé (ne devrait pas arriver : il est retenu) : au plus ancien.
                None => {
                    return state
                        .segments
                        .front()
                        .map_or(Step::Wait, |s| Step::Next(s.info.seq));
                }
            }
        };
        if reader.as_ref().is_none_or(|(s, _)| *s != seq) {
            // Fichier du tampon illisible : disque en défaut, pas un trou.
            let Ok(mut file) = File::open(&path) else {
                return Step::Failed;
            };
            if file.seek(SeekFrom::Start(offset)).is_err() {
                return Step::Failed;
            }
            *reader = Some((seq, file));
        }
        let (_, file) = reader.as_mut().expect("fichier ouvert");
        // `complete` lu avant la lecture : tout ce qui a été écrit est lisible.
        match file.read(buf) {
            Ok(n) if n > 0 => Step::Bytes(n, at),
            Ok(_) if complete && has_next => Step::Next(seq + 1),
            Ok(_) if complete => end.map_or(Step::Wait, Step::Ended),
            Ok(_) => Step::Wait,
            Err(_) => Step::Failed,
        }
    }

    /// Secondes de coupure entre `from` et `to`.
    fn outage_secs(&self, from: f64, to: f64) -> f64 {
        let state = self.lock();
        state
            .outages
            .iter()
            .map(|&(a, b)| {
                let b = b.unwrap_or(to);
                (b.min(to) - a.max(from)).max(0.0)
            })
            .sum()
    }
}

/// Enregistrement inscrit sur un tampon ([`Tuner::attach`]). Le lâcher le
/// désinscrit.
pub struct Follower {
    tuner: Tuner,
    id: u64,
    cursor: (u64, u64),
    start: f64,
}

impl std::fmt::Debug for Follower {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Follower")
            .field("start", &self.start)
            .finish_non_exhaustive()
    }
}

impl Drop for Follower {
    fn drop(&mut self) {
        self.tuner.lock().followers.remove(&self.id);
        self.tuner.shared.control.notify_waiters();
        // Dernier enregistrement d'une copie déjà finie : le tampon part.
        self.tuner.erase_if_unused();
    }
}

impl Follower {
    /// Premier moment copié (secondes Unix) : à passer à
    /// `Recordings::begin_from`, qui compte le temps perdu avant lui.
    pub fn start(&self) -> i64 {
        self.start.floor() as i64
    }

    /// Copie dans le fichier de `job` ce que le tampon a reçu depuis
    /// [`Self::start`], puis la suite au fil de l'eau, jusqu'à l'heure de fin
    /// de `control`. Même bilan que [`super::capture`], à passer à
    /// `Recordings::finish`. Copie arrêtée par l'app ([`Tuner::stop`]) :
    /// [`CaptureEnd::Preempted`].
    pub async fn copy(
        mut self,
        job: &CaptureJob,
        control: &CaptureControl,
        options: &CaptureOptions,
    ) -> CaptureReport {
        let initial = std::fs::metadata(&job.path).map(|m| m.len()).unwrap_or(0);
        control.begin(initial, job.until);
        let mut copy = FollowCopy {
            bytes: initial,
            written: 0,
            since_space_check: 0,
        };
        let end = self.run(job, control, options, &mut copy).await;
        let t = self.tuner.clock();
        let gap = self.tuner.outage_secs(self.start, t);
        CaptureReport {
            end,
            bytes: copy.bytes,
            written: copy.written,
            gap_secs: gap.round() as i64,
            received_secs: (t - self.start - gap).max(0.0).round() as i64,
        }
    }

    async fn run(
        &mut self,
        job: &CaptureJob,
        control: &CaptureControl,
        options: &CaptureOptions,
        copy: &mut FollowCopy,
    ) -> CaptureEnd {
        let mut out = match OpenOptions::new().create(true).append(true).open(&job.path) {
            Ok(file) => file,
            Err(_) => return CaptureEnd::WriteFailed,
        };
        let dir = job.path.parent().unwrap_or(Path::new(".")).to_path_buf();
        let out_of_space = |options: &CaptureOptions| {
            (options.probe)(&dir).is_some_and(|s| s.free_bytes <= s.reserve_bytes())
        };
        if out_of_space(options) {
            return CaptureEnd::OutOfSpace;
        }
        let tuner = self.tuner.clone();
        let mut reader: Option<(u64, File)> = None;
        let mut buf = vec![0u8; FOLLOW_CHUNK];
        loop {
            // Créés avant les vérifications : aucun réveil perdu.
            let data = tuner.shared.data.notified();
            tokio::pin!(data);
            data.as_mut().enable();
            let changes = control.changes();
            tokio::pin!(changes);
            changes.as_mut().enable();
            if control.is_stopped() {
                return CaptureEnd::Stopped;
            }
            let until = control.until() as f64;
            let past_end = now() >= until;
            match tuner.read_next(&mut reader, &self.cursor, &mut buf) {
                Step::Bytes(n, at) => {
                    // Rattrapage arrivé au-delà de l'heure de fin.
                    if past_end && at >= until {
                        return CaptureEnd::Finished;
                    }
                    if out.write_all(&buf[..n]).is_err() {
                        return CaptureEnd::WriteFailed;
                    }
                    self.cursor.1 += n as u64;
                    copy.bytes += n as u64;
                    copy.written += n as u64;
                    copy.since_space_check += n as u64;
                    control.set_bytes(copy.bytes);
                    if copy.since_space_check >= options.space_check_bytes {
                        copy.since_space_check = 0;
                        if out_of_space(options) {
                            return CaptureEnd::OutOfSpace;
                        }
                    }
                    // Rattraper une heure de tampon ne monopolise pas le fil.
                    tokio::task::yield_now().await;
                    continue;
                }
                Step::Next(seq) => {
                    self.cursor = (seq, 0);
                    reader = None;
                    tuner.lock().followers.insert(self.id, seq);
                    // Les fichiers déjà copiés peuvent partir.
                    tuner.shared.control.notify_waiters();
                    continue;
                }
                Step::Failed => return CaptureEnd::WriteFailed,
                Step::Ended(end) => {
                    return match end {
                        _ if past_end => CaptureEnd::Finished,
                        // L'app a repris la connexion : incomplet, pas un arrêt
                        // à la main.
                        TuneEnd::Stopped => CaptureEnd::Preempted,
                        TuneEnd::OutOfSpace => CaptureEnd::OutOfSpace,
                        TuneEnd::WriteFailed => CaptureEnd::WriteFailed,
                        TuneEnd::OpenFailed | TuneEnd::ConnectionLost => CaptureEnd::ConnectionLost,
                    };
                }
                Step::Wait => {
                    if past_end {
                        return CaptureEnd::Finished;
                    }
                }
            }
            let deadline = Duration::from_secs_f64((until - now()).max(0.0));
            tokio::select! {
                () = &mut data => {}
                () = &mut changes => {}
                () = tokio::time::sleep(deadline) => {}
            }
        }
    }
}

enum Session {
    Lost { healthy: bool },
    Done(TuneEnd),
}

enum Step {
    /// Octets lus, et moment reçu à l'octet de départ.
    Bytes(usize, f64),
    Next(u64),
    Wait,
    Ended(TuneEnd),
    /// Fichier du tampon impossible à lire.
    Failed,
}

struct FollowCopy {
    bytes: u64,
    written: u64,
    since_space_check: u64,
}

fn find(state: &State, seq: u64) -> Option<&Seg> {
    let first = state.segments.front()?.info.seq;
    let seg = state.segments.get(seq.checked_sub(first)? as usize)?;
    (seg.info.seq == seq).then_some(seg)
}

/// Moment reçu à l'octet `offset` d'un fichier : au prorata des octets
/// entre deux points d'index.
fn time_in(seg: &Seg, offset: u64) -> f64 {
    let index = seg.marks.partition_point(|&(_, o)| o <= offset);
    let (t, o) = seg.marks[index.saturating_sub(1)];
    let next = seg
        .marks
        .get(index)
        .copied()
        .unwrap_or((seg.info.end, seg.info.bytes));
    if next.1 > o && offset > o {
        t + (next.0 - t) * (offset.min(next.1) - o) as f64 / (next.1 - o) as f64
    } else {
        t
    }
}

fn locate(state: &State, at: f64) -> Option<Spot> {
    let index = state
        .segments
        .partition_point(|s| s.info.start <= at)
        .saturating_sub(1);
    let seg = state.segments.get(index)?;
    let mark = seg
        .marks
        .partition_point(|&(t, _)| t <= at)
        .saturating_sub(1);
    let (t, offset) = seg.marks[mark];
    Some(Spot {
        seq: seg.info.seq,
        run: seg.info.run,
        path: seg.info.path.clone(),
        offset,
        at: t,
    })
}

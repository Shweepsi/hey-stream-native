//! Copie du flux d'une chaîne dans un fichier, telle quelle (.ts), avec
//! reconnexion.
//!
//! Après une coupure, la copie reprend **dans le même fichier**, avec des
//! attentes croissantes entre les essais (le fournisseur bloque les requêtes
//! en rafale), puis un essai par minute jusqu'à l'heure de fin : une panne
//! de quelques minutes du fournisseur ne fait pas perdre la suite du
//! programme (décision du 2026-11-01). L'URL du flux contient les
//! identifiants : elle n'est ni gardée ni journalisée, et les erreurs
//! réseau sont remontées sans elle.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio::sync::Notify;

use super::space::{VolumeSpace, volume_space};
use crate::client::{XtreamClient, http_builder};
use crate::url::StreamKind;

/// Ce qu'il faut pour copier un enregistrement : rendu par
/// `Recordings::begin`, sans URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptureJob {
    pub recording_id: i64,
    pub stream_id: i64,
    /// Fichier écrit (complété s'il existe déjà : reprise après un arrêt de
    /// l'app).
    pub path: PathBuf,
    /// Fin de la copie, en secondes Unix.
    pub until: i64,
}

/// Comment la copie s'est arrêtée.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CaptureEnd {
    /// Heure de fin atteinte.
    Finished,
    /// Arrêtée à la main ([`CaptureControl::stop`]).
    Stopped,
    /// Réserve du disque atteinte : arrêt propre, fichier gardé.
    OutOfSpace,
    /// Client réseau impossible à créer : aucun essai possible.
    ConnectionLost,
    /// Fichier impossible à écrire (volume débranché, droits).
    WriteFailed,
}

/// Bilan d'une copie, à passer à `Recordings::finish`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureReport {
    pub end: CaptureEnd,
    /// Taille du fichier à la fin.
    pub bytes: u64,
    /// Octets écrits par cette copie.
    pub written: u64,
    /// Secondes sans données (coupures, attente de la première donnée).
    pub gap_secs: i64,
    /// Secondes pendant lesquelles des données sont arrivées.
    pub received_secs: i64,
}

/// Arrêt et avancement d'une copie en cours, partagés avec l'interface
/// (bouton Arrêter de la notification, taille affichée).
#[derive(Debug, Clone, Default)]
pub struct CaptureControl {
    inner: Arc<ControlInner>,
}

#[derive(Debug, Default)]
struct ControlInner {
    stopped: AtomicBool,
    bytes: AtomicU64,
    until: AtomicI64,
    notify: Notify,
}

impl CaptureControl {
    pub fn new() -> Self {
        Self::default()
    }

    /// Arrête la copie au plus vite ; le fichier est gardé.
    pub fn stop(&self) {
        self.inner.stopped.store(true, Ordering::SeqCst);
        self.inner.notify.notify_waiters();
    }

    /// Nouvelle heure de fin de la copie en cours : un programme ajouté à la
    /// suite la prolonge, un enregistrement d'une autre chaîne juste après
    /// raccourcit sa marge (voir `Recordings::due`).
    pub fn set_until(&self, until: i64) {
        self.inner.until.store(until, Ordering::SeqCst);
        self.inner.notify.notify_waiters();
    }

    /// Heure de fin de la copie, en secondes Unix.
    pub fn until(&self) -> i64 {
        self.inner.until.load(Ordering::SeqCst)
    }

    pub fn is_stopped(&self) -> bool {
        self.inner.stopped.load(Ordering::SeqCst)
    }

    /// Taille actuelle du fichier.
    pub fn bytes(&self) -> u64 {
        self.inner.bytes.load(Ordering::Relaxed)
    }

    async fn stopped(&self) {
        loop {
            let notified = self.inner.notify.notified();
            if self.is_stopped() {
                return;
            }
            notified.await;
        }
    }

    /// Arrêt ou nouvelle heure de fin.
    async fn changed(&self) {
        self.inner.notify.notified().await;
    }
}

/// Mesure de la place libre, remplaçable dans les tests.
pub type SpaceProbe = Arc<dyn Fn(&Path) -> Option<VolumeSpace> + Send + Sync>;

/// Réglages de la copie. Les valeurs par défaut sont celles de l'app.
#[derive(Clone)]
pub struct CaptureOptions {
    /// Attente avant le premier nouvel essai, doublée à chaque échec.
    pub first_retry: Duration,
    /// Attente maximale entre deux essais, gardée ensuite jusqu'à l'heure
    /// de fin : jamais plus d'un essai par `max_retry` en cas de panne.
    pub max_retry: Duration,
    /// Sans données pendant ce temps, la connexion est refaite.
    pub stall: Duration,
    /// Une connexion qui a fourni des données pendant ce temps ramène
    /// l'attente suivante à `first_retry`.
    pub healthy_after: Duration,
    /// Place libre vérifiée tous les tant d'octets écrits.
    pub space_check_bytes: u64,
    pub probe: SpaceProbe,
}

impl Default for CaptureOptions {
    fn default() -> Self {
        Self {
            first_retry: Duration::from_secs(2),
            max_retry: Duration::from_secs(60),
            stall: Duration::from_secs(20),
            healthy_after: Duration::from_secs(30),
            space_check_bytes: 32 * 1024 * 1024,
            probe: Arc::new(volume_space),
        }
    }
}

impl std::fmt::Debug for CaptureOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CaptureOptions")
            .field("first_retry", &self.first_retry)
            .field("max_retry", &self.max_retry)
            .finish_non_exhaustive()
    }
}

/// Copie le flux de `job` jusqu'à son heure de fin, un arrêt à la main, la
/// réserve du disque ou une écriture refusée ; les coupures sont réessayées
/// jusqu'à l'heure de fin. Ne renvoie jamais d'erreur : tout
/// est dans le bilan, que `Recordings::finish` enregistre.
pub async fn capture(
    client: &XtreamClient,
    job: &CaptureJob,
    control: &CaptureControl,
    options: &CaptureOptions,
) -> CaptureReport {
    let mut copy = Copy::new(job, control, options);
    let end = copy.run(client).await;
    copy.report(end)
}

struct Copy<'a> {
    job: &'a CaptureJob,
    control: &'a CaptureControl,
    options: &'a CaptureOptions,
    bytes: u64,
    written: u64,
    since_space_check: u64,
    gap: f64,
    received: f64,
    /// Début de la coupure en cours (ou de l'attente de la première donnée).
    outage_since: Option<f64>,
    /// Arrivée de la première donnée de la connexion en cours.
    connected_since: Option<f64>,
}

enum Session {
    /// Le flux s'est arrêté ou a calé : nouvel essai.
    Lost {
        healthy: bool,
    },
    Done(CaptureEnd),
}

impl<'a> Copy<'a> {
    fn new(job: &'a CaptureJob, control: &'a CaptureControl, options: &'a CaptureOptions) -> Self {
        let bytes = std::fs::metadata(&job.path).map(|m| m.len()).unwrap_or(0);
        control.inner.bytes.store(bytes, Ordering::Relaxed);
        control.inner.until.store(job.until, Ordering::SeqCst);
        Copy {
            job,
            control,
            options,
            bytes,
            written: 0,
            since_space_check: 0,
            gap: 0.0,
            received: 0.0,
            outage_since: Some(now()),
            connected_since: None,
        }
    }

    async fn run(&mut self, client: &XtreamClient) -> CaptureEnd {
        // Pas de délai global : un enregistrement dure des heures.
        let Ok(http) = http_builder().build() else {
            return CaptureEnd::ConnectionLost;
        };
        let url = client.stream_url(StreamKind::Live, self.job.stream_id, Some("ts"));
        let mut file = match OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.job.path)
        {
            Ok(file) => file,
            Err(_) => return CaptureEnd::WriteFailed,
        };
        if self.out_of_space() {
            return CaptureEnd::OutOfSpace;
        }
        // Échecs de suite : règlent seulement l'attente avant le prochain essai.
        let mut failures = 0u32;
        loop {
            if self.control.is_stopped() {
                return CaptureEnd::Stopped;
            }
            if now() >= self.control.until() as f64 {
                return CaptureEnd::Finished;
            }
            match self.session(&http, url.clone(), &mut file).await {
                Session::Done(end) => return end,
                Session::Lost { healthy } => {
                    failures = if healthy {
                        1
                    } else {
                        failures.saturating_add(1)
                    };
                }
            }
            let wait = self
                .options
                .first_retry
                .saturating_mul(1 << (failures - 1).min(16))
                .min(self.options.max_retry);
            if let Some(end) = self.wait(wait).await {
                return end;
            }
        }
    }

    /// Une connexion au flux, jusqu'à sa perte ou la fin de la copie.
    async fn session(
        &mut self,
        http: &reqwest::Client,
        url: url::Url,
        file: &mut std::fs::File,
    ) -> Session {
        let send = tokio::time::timeout(self.options.stall, http.get(url).send());
        tokio::pin!(send);
        let response = loop {
            tokio::select! {
                () = self.control.stopped() => return Session::Done(CaptureEnd::Stopped),
                () = self.control.changed() => continue,
                () = tokio::time::sleep(self.deadline()) => {
                    return Session::Done(CaptureEnd::Finished);
                }
                response = &mut send => break response,
            }
        };
        let mut response = match response {
            Ok(Ok(response)) if response.status().is_success() => response,
            _ => return Session::Lost { healthy: false },
        };
        loop {
            let chunk = tokio::select! {
                () = self.control.stopped() => return Session::Done(CaptureEnd::Stopped),
                // Heure de fin changée : on recalcule l'attente (lire un
                // morceau peut être abandonné et repris sans rien perdre).
                () = self.control.changed() => continue,
                () = tokio::time::sleep(self.deadline()) => {
                    return Session::Done(CaptureEnd::Finished);
                }
                chunk = tokio::time::timeout(self.options.stall, response.chunk()) => chunk,
            };
            let chunk = match chunk {
                Ok(Ok(Some(chunk))) => chunk,
                // Fin du flux, erreur ou flux qui cale.
                _ => {
                    return Session::Lost {
                        healthy: self.lose_connection(),
                    };
                }
            };
            if chunk.is_empty() {
                continue;
            }
            if let Some(since) = self.outage_since.take() {
                let t = now();
                self.gap += t - since;
                self.connected_since = Some(t);
            }
            if file.write_all(&chunk).is_err() {
                return Session::Done(CaptureEnd::WriteFailed);
            }
            let len = chunk.len() as u64;
            self.bytes += len;
            self.written += len;
            self.since_space_check += len;
            self.control
                .inner
                .bytes
                .store(self.bytes, Ordering::Relaxed);
            if self.since_space_check >= self.options.space_check_bytes {
                self.since_space_check = 0;
                if self.out_of_space() {
                    return Session::Done(CaptureEnd::OutOfSpace);
                }
            }
        }
    }

    /// Connexion perdue : début d'une coupure. Vrai si elle avait fourni des
    /// données assez longtemps pour remettre les échecs à zéro.
    fn lose_connection(&mut self) -> bool {
        let Some(since) = self.connected_since.take() else {
            return false;
        };
        let t = now();
        self.received += t - since;
        self.outage_since = Some(t);
        t - since >= self.options.healthy_after.as_secs_f64()
    }

    /// Attente entre deux essais ; `Some` si la copie doit s'arrêter.
    async fn wait(&self, wait: Duration) -> Option<CaptureEnd> {
        let pause = tokio::time::sleep(wait);
        tokio::pin!(pause);
        loop {
            tokio::select! {
                () = self.control.stopped() => return Some(CaptureEnd::Stopped),
                () = self.control.changed() => continue,
                () = tokio::time::sleep(self.deadline()) => return Some(CaptureEnd::Finished),
                () = &mut pause => return None,
            }
        }
    }

    /// Temps restant avant l'heure de fin.
    fn deadline(&self) -> Duration {
        Duration::from_secs_f64((self.control.until() as f64 - now()).max(0.0))
    }

    fn out_of_space(&self) -> bool {
        let dir = self.job.path.parent().unwrap_or(Path::new("."));
        match (self.options.probe)(dir) {
            Some(space) => space.free_bytes <= space.reserve_bytes(),
            // Mesure impossible : on continue, une écriture refusée arrêtera
            // la copie.
            None => false,
        }
    }

    fn report(mut self, end: CaptureEnd) -> CaptureReport {
        let t = now();
        if let Some(since) = self.connected_since.take() {
            self.received += t - since;
        }
        if let Some(since) = self.outage_since.take() {
            self.gap += t - since;
        }
        CaptureReport {
            end,
            bytes: self.bytes,
            written: self.written,
            gap_secs: self.gap.round() as i64,
            received_secs: self.received.round() as i64,
        }
    }
}

fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

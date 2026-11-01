//! Enregistrement programmé (Android seulement, `docs/DECISIONS.md`).
//!
//! [`RecordingStore`] est indépendant de l'abonnement : sa base
//! (`recordings.db`) survit à un changement d'identifiant, qui n'annule que
//! les programmations. La copie du flux, elle, a besoin de l'abonnement :
//! [`HeyStreamCore::record`](crate::HeyStreamCore::record).
//!
//! Méthodes bloquantes, comme le reste des liaisons ; `record` dure tout
//! l'enregistrement et s'appelle depuis un fil à part (service Android).

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use xtream_core as xc;

use crate::{HeyStreamError, Result, now};

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct RecordingMargins {
    pub before_secs: i64,
    pub after_secs: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GuideProgramme {
    pub title: String,
    pub description: Option<String>,
    pub start: i64,
    pub stop: i64,
}

/// Programmation depuis le guide d'une chaîne.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScheduleRequest {
    pub stream_id: i64,
    pub channel: String,
    /// Voir `HeyStreamCore::is_adult`.
    pub is_adult: bool,
    pub programme: GuideProgramme,
    pub margins: RecordingMargins,
    /// Plus ancien moment du différé de cette chaîne, si on la regarde :
    /// l'enregistrement peut commencer dans ce passé.
    #[uniffi(default = None)]
    pub buffered_from: Option<i64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SchedulePreview {
    pub merged_into: Option<i64>,
    pub start: i64,
    pub stop: i64,
    pub margins_shortened: bool,
    /// Chaîne mesurée pendant l'aperçu ; sinon taille et place manquante à 0.
    pub measured: bool,
    pub estimated_bytes: u64,
    /// Place qui manquerait sur le disque choisi ; 0 si tout tient.
    pub missing_bytes: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RecordingState {
    Scheduled,
    Recording,
    Done,
    Incomplete,
    Failed,
    Missed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RecordingProblem {
    Gaps,
    OutOfSpace,
    ConnectionLost,
    WriteFailed,
    VolumeMissing,
    Interrupted,
    NotStarted,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Recording {
    pub id: i64,
    pub stream_id: i64,
    pub channel: String,
    pub is_adult: bool,
    pub programmes: Vec<GuideProgramme>,
    pub start: i64,
    pub stop: i64,
    pub state: RecordingState,
    pub problem: Option<RecordingProblem>,
    /// Fichier `.ts` (qu'il existe déjà ou non).
    pub path: String,
    pub volume_missing: bool,
    pub bytes: u64,
    pub gap_secs: i64,
    pub started_at: Option<i64>,
    pub ended_at: Option<i64>,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub watched: bool,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct DueRecording {
    pub id: i64,
    pub stream_id: i64,
    pub start: i64,
    pub stop: i64,
}

/// Copie à lancer ([`HeyStreamCore::record`](crate::HeyStreamCore::record)) ;
/// sans URL.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CaptureJob {
    pub recording_id: i64,
    pub stream_id: i64,
    pub path: String,
    pub until: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CaptureEnd {
    Finished,
    Stopped,
    OutOfSpace,
    ConnectionLost,
    WriteFailed,
    /// Copie du différé reprise par l'app : incomplet.
    Preempted,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct CaptureReport {
    pub end: CaptureEnd,
    pub bytes: u64,
    pub written: u64,
    pub gap_secs: i64,
    pub received_secs: i64,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct RecordingUsage {
    pub used_bytes: u64,
    pub usable_bytes: u64,
    pub remaining_secs: i64,
    /// Moins d'une heure d'enregistrement possible.
    pub low: bool,
}

#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct VolumeSpace {
    pub total_bytes: u64,
    pub free_bytes: u64,
}

/// Taille et place libre du volume qui contient `dir` ; `None` s'il est
/// absent (débranché).
#[uniffi::export]
pub fn volume_space(dir: String) -> Option<VolumeSpace> {
    xc::volume_space(Path::new(&dir)).map(Into::into)
}

/// Marges proposées la première fois (2 min, 5 min).
#[uniffi::export]
pub fn default_recording_margins() -> RecordingMargins {
    xc::DEFAULT_MARGINS.into()
}

/// Planning et liste des enregistrements de l'appareil.
#[derive(uniffi::Object)]
pub struct RecordingStore {
    inner: Mutex<xc::Recordings>,
}

#[uniffi::export]
impl RecordingStore {
    /// Ouvre `data_dir/recordings.db`. Ne fait aucune requête réseau.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Result<Arc<Self>> {
        let store = xc::Recordings::open(PathBuf::from(data_dir).join("recordings.db"))?;
        Ok(Arc::new(Self {
            inner: Mutex::new(store),
        }))
    }

    /// Dossier des prochains enregistrements (disque choisi dans Réglages),
    /// créé s'il n'existe pas.
    pub fn set_directory(&self, dir: String) -> Result<()> {
        std::fs::create_dir_all(&dir).map_err(|_| HeyStreamError::VolumeMissing)?;
        Ok(self.lock().set_directory(Path::new(&dir))?)
    }

    pub fn directory(&self) -> Result<Option<String>> {
        Ok(self
            .lock()
            .directory()?
            .map(|p| p.to_string_lossy().into_owned()))
    }

    pub fn last_margins(&self) -> Result<RecordingMargins> {
        Ok(self.lock().last_margins()?.into())
    }

    /// Ce que donnerait `schedule` (fusion, marges, taille, place manquante
    /// sur le disque choisi). Rien n'est enregistré. `buffer_bytes` : place
    /// prise par le différé sur ce disque, comptée comme libre (il la cède).
    pub fn preview(&self, request: ScheduleRequest, buffer_bytes: u64) -> Result<SchedulePreview> {
        let mut store = self.lock();
        let dir = store.directory()?.ok_or(HeyStreamError::VolumeMissing)?;
        let _ = std::fs::create_dir_all(&dir);
        let mut space = xc::volume_space(&dir).ok_or(HeyStreamError::VolumeMissing)?;
        space.free_bytes = space.free_bytes.saturating_add(buffer_bytes);
        Ok(store.preview(&request.into(), space, now())?.into())
    }

    /// Programme ; renvoie l'enregistrement (existant s'il est prolongé).
    pub fn schedule(&self, request: ScheduleRequest) -> Result<i64> {
        Ok(self.lock().schedule(&request.into(), now())?)
    }

    pub fn cancel(&self, id: i64) -> Result<()> {
        Ok(self.lock().cancel(id)?)
    }

    /// Déconnexion, changement d'identifiant : programmations annulées,
    /// fichiers gardés.
    pub fn cancel_all_scheduled(&self) -> Result<u64> {
        Ok(self.lock().cancel_all_scheduled()? as u64)
    }

    /// Début réel du prochain enregistrement programmé (alarme).
    pub fn next_start(&self) -> Result<Option<i64>> {
        Ok(self.lock().next_start(now())?)
    }

    pub fn due(&self) -> Result<Option<DueRecording>> {
        Ok(self.lock().due(now())?.map(Into::into))
    }

    /// Démarre ou reprend la copie : la place est revérifiée sur le disque
    /// de l'enregistrement.
    pub fn begin(&self, id: i64) -> Result<CaptureJob> {
        self.begin_from(id, None)
    }

    /// Comme `begin`, pour un enregistrement qui suit le différé de sa
    /// chaîne : `buffered_from` est `TimeshiftFollower::start` (seul le temps
    /// avant lui est perdu).
    pub fn begin_from(&self, id: i64, buffered_from: Option<i64>) -> Result<CaptureJob> {
        let mut store = self.lock();
        let recording = store
            .recording(id)?
            .ok_or(HeyStreamError::RecordingNotFound)?;
        let space = recording.path.parent().and_then(xc::volume_space);
        Ok(store.begin_from(id, space, now(), buffered_from)?.into())
    }

    pub fn update_progress(&self, id: i64, bytes: u64) -> Result<()> {
        Ok(self.lock().update_progress(id, bytes, now())?)
    }

    pub fn finish(&self, id: i64, report: CaptureReport) -> Result<Recording> {
        Ok(self.lock().finish(id, &report.into(), now())?.into())
    }

    /// Sans copie en cours : manqués et interrompus, à signaler.
    pub fn recover(&self) -> Result<Vec<Recording>> {
        Ok(self
            .lock()
            .recover(now())?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn recording(&self, id: i64) -> Result<Option<Recording>> {
        Ok(self.lock().recording(id)?.map(Into::into))
    }

    pub fn scheduled(&self, parental_unlocked: bool) -> Result<Vec<Recording>> {
        Ok(self
            .lock()
            .scheduled(parental_unlocked)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn recorded(&self, parental_unlocked: bool) -> Result<Vec<Recording>> {
        Ok(self
            .lock()
            .recorded(parental_unlocked)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    pub fn delete(&self, id: i64) -> Result<()> {
        Ok(self.lock().delete(id)?)
    }

    pub fn set_position(&self, id: i64, position_ms: i64, duration_ms: i64) -> Result<()> {
        Ok(self.lock().set_position(id, position_ms, duration_ms)?)
    }

    /// Jauge du disque choisi ; `None` sans disque ou s'il est débranché.
    pub fn usage(&self) -> Result<Option<RecordingUsage>> {
        let store = self.lock();
        let Some(dir) = store.directory()? else {
            return Ok(None);
        };
        let Some(space) = xc::volume_space(&dir) else {
            return Ok(None);
        };
        Ok(Some(store.usage(&dir, space)?.into()))
    }

    /// Enregistrements à proposer pour libérer `missing_bytes` sur le disque
    /// choisi (déjà vus d'abord).
    pub fn cleanup_candidates(
        &self,
        missing_bytes: u64,
        parental_unlocked: bool,
    ) -> Result<Vec<Recording>> {
        let store = self.lock();
        let Some(dir) = store.directory()? else {
            return Ok(Vec::new());
        };
        Ok(store
            .cleanup_candidates(&dir, missing_bytes, parental_unlocked)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Débit mesuré pendant l'aperçu d'une chaîne (octets sur secondes de
    /// flux) : seule source de l'estimation, la dernière mesure remplace la
    /// précédente.
    pub fn learn_rate(&self, stream_id: i64, bytes: u64, secs: f64) -> Result<()> {
        Ok(self.lock().learn_rate(stream_id, bytes, secs)?)
    }
}

impl RecordingStore {
    fn lock(&self) -> MutexGuard<'_, xc::Recordings> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// Arrêt et avancement d'une copie en cours, partagés avec l'interface.
#[derive(uniffi::Object, Default)]
pub struct RecordingControl {
    pub(crate) inner: xc::CaptureControl,
}

#[uniffi::export]
impl RecordingControl {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Arrête la copie au plus vite ; le fichier est gardé.
    pub fn stop(&self) {
        self.inner.stop();
    }

    pub fn is_stopped(&self) -> bool {
        self.inner.is_stopped()
    }

    /// Nouvelle heure de fin (fenêtre changée par une programmation).
    pub fn set_until(&self, until: i64) {
        self.inner.set_until(until);
    }

    pub fn until(&self) -> i64 {
        self.inner.until()
    }

    /// Taille actuelle du fichier.
    pub fn bytes(&self) -> u64 {
        self.inner.bytes()
    }
}

// --- Conversions -----------------------------------------------------------------

impl From<xc::Margins> for RecordingMargins {
    fn from(m: xc::Margins) -> Self {
        Self {
            before_secs: m.before_secs,
            after_secs: m.after_secs,
        }
    }
}

impl From<RecordingMargins> for xc::Margins {
    fn from(m: RecordingMargins) -> Self {
        Self {
            before_secs: m.before_secs,
            after_secs: m.after_secs,
        }
    }
}

impl From<xc::GuideProgramme> for GuideProgramme {
    fn from(p: xc::GuideProgramme) -> Self {
        Self {
            title: p.title,
            description: p.description,
            start: p.start,
            stop: p.stop,
        }
    }
}

impl From<GuideProgramme> for xc::GuideProgramme {
    fn from(p: GuideProgramme) -> Self {
        Self {
            title: p.title,
            description: p.description,
            start: p.start,
            stop: p.stop,
        }
    }
}

impl From<ScheduleRequest> for xc::ScheduleRequest {
    fn from(r: ScheduleRequest) -> Self {
        Self {
            stream_id: r.stream_id,
            channel: r.channel,
            is_adult: r.is_adult,
            programme: r.programme.into(),
            margins: r.margins.into(),
            buffered_from: r.buffered_from,
        }
    }
}

impl From<xc::SchedulePreview> for SchedulePreview {
    fn from(p: xc::SchedulePreview) -> Self {
        Self {
            merged_into: p.merged_into,
            start: p.start,
            stop: p.stop,
            margins_shortened: p.margins_shortened,
            measured: p.measured,
            estimated_bytes: p.estimated_bytes,
            missing_bytes: p.missing_bytes,
        }
    }
}

impl From<xc::RecordingState> for RecordingState {
    fn from(s: xc::RecordingState) -> Self {
        match s {
            xc::RecordingState::Scheduled => Self::Scheduled,
            xc::RecordingState::Recording => Self::Recording,
            xc::RecordingState::Done => Self::Done,
            xc::RecordingState::Incomplete => Self::Incomplete,
            xc::RecordingState::Failed => Self::Failed,
            xc::RecordingState::Missed => Self::Missed,
        }
    }
}

impl From<xc::RecordingProblem> for RecordingProblem {
    fn from(p: xc::RecordingProblem) -> Self {
        match p {
            xc::RecordingProblem::Gaps => Self::Gaps,
            xc::RecordingProblem::OutOfSpace => Self::OutOfSpace,
            xc::RecordingProblem::ConnectionLost => Self::ConnectionLost,
            xc::RecordingProblem::WriteFailed => Self::WriteFailed,
            xc::RecordingProblem::VolumeMissing => Self::VolumeMissing,
            xc::RecordingProblem::Interrupted => Self::Interrupted,
            xc::RecordingProblem::NotStarted => Self::NotStarted,
        }
    }
}

impl From<xc::Recording> for Recording {
    fn from(r: xc::Recording) -> Self {
        Self {
            id: r.id,
            stream_id: r.stream_id,
            channel: r.channel,
            is_adult: r.is_adult,
            programmes: r.programmes.into_iter().map(Into::into).collect(),
            start: r.start,
            stop: r.stop,
            state: r.state.into(),
            problem: r.problem.map(Into::into),
            path: r.path.to_string_lossy().into_owned(),
            volume_missing: r.volume_missing,
            bytes: r.bytes,
            gap_secs: r.gap_secs,
            started_at: r.started_at,
            ended_at: r.ended_at,
            position_ms: r.position_ms,
            duration_ms: r.duration_ms,
            watched: r.watched,
        }
    }
}

impl From<xc::DueRecording> for DueRecording {
    fn from(d: xc::DueRecording) -> Self {
        Self {
            id: d.id,
            stream_id: d.stream_id,
            start: d.start,
            stop: d.stop,
        }
    }
}

impl From<xc::CaptureJob> for CaptureJob {
    fn from(j: xc::CaptureJob) -> Self {
        Self {
            recording_id: j.recording_id,
            stream_id: j.stream_id,
            path: j.path.to_string_lossy().into_owned(),
            until: j.until,
        }
    }
}

impl From<CaptureJob> for xc::CaptureJob {
    fn from(j: CaptureJob) -> Self {
        Self {
            recording_id: j.recording_id,
            stream_id: j.stream_id,
            path: PathBuf::from(j.path),
            until: j.until,
        }
    }
}

impl From<xc::CaptureEnd> for CaptureEnd {
    fn from(e: xc::CaptureEnd) -> Self {
        match e {
            xc::CaptureEnd::Finished => Self::Finished,
            xc::CaptureEnd::Stopped => Self::Stopped,
            xc::CaptureEnd::OutOfSpace => Self::OutOfSpace,
            xc::CaptureEnd::ConnectionLost => Self::ConnectionLost,
            xc::CaptureEnd::WriteFailed => Self::WriteFailed,
            xc::CaptureEnd::Preempted => Self::Preempted,
        }
    }
}

impl From<CaptureEnd> for xc::CaptureEnd {
    fn from(e: CaptureEnd) -> Self {
        match e {
            CaptureEnd::Finished => Self::Finished,
            CaptureEnd::Stopped => Self::Stopped,
            CaptureEnd::OutOfSpace => Self::OutOfSpace,
            CaptureEnd::ConnectionLost => Self::ConnectionLost,
            CaptureEnd::WriteFailed => Self::WriteFailed,
            CaptureEnd::Preempted => Self::Preempted,
        }
    }
}

impl From<xc::CaptureReport> for CaptureReport {
    fn from(r: xc::CaptureReport) -> Self {
        Self {
            end: r.end.into(),
            bytes: r.bytes,
            written: r.written,
            gap_secs: r.gap_secs,
            received_secs: r.received_secs,
        }
    }
}

impl From<CaptureReport> for xc::CaptureReport {
    fn from(r: CaptureReport) -> Self {
        Self {
            end: r.end.into(),
            bytes: r.bytes,
            written: r.written,
            gap_secs: r.gap_secs,
            received_secs: r.received_secs,
        }
    }
}

impl From<xc::RecordingUsage> for RecordingUsage {
    fn from(u: xc::RecordingUsage) -> Self {
        Self {
            used_bytes: u.used_bytes,
            usable_bytes: u.usable_bytes,
            remaining_secs: u.remaining_secs,
            low: u.low,
        }
    }
}

impl From<xc::VolumeSpace> for VolumeSpace {
    fn from(v: xc::VolumeSpace) -> Self {
        Self {
            total_bytes: v.total_bytes,
            free_bytes: v.free_bytes,
        }
    }
}

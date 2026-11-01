//! Enregistrement programmé (Android seulement, `docs/DECISIONS.md`,
//! « Enregistrement programmé »).
//!
//! Le planning et la liste des enregistrements vivent dans leur propre base,
//! à part du catalogue : un changement d'identifiant efface le catalogue et
//! annule les programmations ([`Recordings::cancel_all_scheduled`]), mais
//! les fichiers enregistrés restent. Le planning garde le numéro du flux,
//! jamais son URL.
//!
//! Déroulé côté app : [`Recordings::preview`] puis [`Recordings::schedule`]
//! depuis le guide ; une alarme à [`Recordings::next_start`] ; à l'heure,
//! [`Recordings::due`], [`Recordings::begin`], [`capture`] puis
//! [`Recordings::finish`]. Au lancement (aucune copie en cours),
//! [`Recordings::recover`] signale les enregistrements manqués ou
//! interrompus.

mod capture;
mod plan;
mod space;

use std::path::{Path, PathBuf};

use rusqlite::{Connection, OptionalExtension, Transaction, params};

pub use capture::{
    CaptureControl, CaptureEnd, CaptureJob, CaptureOptions, CaptureReport, SpaceProbe, capture,
};
pub use space::{DEFAULT_BYTES_PER_HOUR, VolumeSpace, volume_space};

use crate::error::{Error, Result};
use plan::{Planned, programmes_overlap, slots, windows_touch};

/// Marges proposées la première fois : 2 min avant, 5 min après.
pub const DEFAULT_MARGINS: Margins = Margins {
    before_secs: 2 * 60,
    after_secs: 5 * 60,
};
/// Marge maximale, avant comme après : 1 h.
pub const MAX_MARGIN_SECS: i64 = 60 * 60;
/// Au-delà d'une minute sans données, l'enregistrement est incomplet.
pub const MAX_GAP_SECS: i64 = 60;
/// Sous une heure d'enregistrement possible, l'app prévient.
pub const LOW_SPACE_SECS: i64 = 60 * 60;
/// Lu au-delà de 95 %, un enregistrement est vu.
const WATCHED_RATIO: f64 = 0.95;
/// Une mesure de débit plus courte n'est pas retenue.
const MIN_RATE_SAMPLE_SECS: f64 = 30.0;
/// Poids maximal de l'historique d'une chaîne : les mesures récentes
/// comptent autant que les trois dernières heures.
const MAX_RATE_WEIGHT_SECS: f64 = 3.0 * 3600.0;

const MIGRATIONS: &[&str] = &["
    CREATE TABLE recording (
        id INTEGER PRIMARY KEY,
        stream_id INTEGER NOT NULL,
        channel TEXT NOT NULL,
        is_adult INTEGER NOT NULL,
        start INTEGER NOT NULL,
        stop INTEGER NOT NULL,
        state TEXT NOT NULL,
        problem TEXT,
        dir TEXT NOT NULL,
        bytes INTEGER NOT NULL DEFAULT 0,
        gap_secs INTEGER NOT NULL DEFAULT 0,
        started_at INTEGER,
        ended_at INTEGER,
        progress_at INTEGER,
        position_ms INTEGER NOT NULL DEFAULT 0,
        duration_ms INTEGER NOT NULL DEFAULT 0,
        watched INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL
    );
    CREATE INDEX recording_by_state ON recording (state, start);
    CREATE TABLE programme (
        recording_id INTEGER NOT NULL REFERENCES recording (id) ON DELETE CASCADE,
        start INTEGER NOT NULL,
        stop INTEGER NOT NULL,
        title TEXT NOT NULL,
        description TEXT,
        PRIMARY KEY (recording_id, start)
    );
    CREATE TABLE channel_rate (
        stream_id INTEGER PRIMARY KEY,
        bytes_per_sec REAL NOT NULL,
        weight_secs REAL NOT NULL
    );
    CREATE TABLE setting (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
"];

const MARGIN_BEFORE_KEY: &str = "margin_before";
const MARGIN_AFTER_KEY: &str = "margin_after";
const DIRECTORY_KEY: &str = "directory";

/// Marges autour des programmes, en secondes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Margins {
    pub before_secs: i64,
    pub after_secs: i64,
}

/// Programme du guide.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GuideProgramme {
    pub title: String,
    pub description: Option<String>,
    /// Début et fin, en secondes Unix, figés à la programmation.
    pub start: i64,
    pub stop: i64,
}

/// Demande d'enregistrement, faite depuis le guide d'une chaîne.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScheduleRequest {
    pub stream_id: i64,
    /// Nom affiché de la chaîne, gardé pour la liste (le catalogue peut
    /// changer ou être effacé).
    pub channel: String,
    /// Chaîne ou catégorie adulte : la marque reste avec le fichier.
    pub is_adult: bool,
    pub programme: GuideProgramme,
    pub margins: Margins,
}

/// Ce que donnerait une programmation, sans rien enregistrer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SchedulePreview {
    /// Enregistrement de la même chaîne prolongé (programmes qui se suivent).
    pub merged_into: Option<i64>,
    /// Fenêtre réelle, marges raccourcies s'il le faut.
    pub start: i64,
    pub stop: i64,
    /// Marges raccourcies à cause d'un autre enregistrement.
    pub margins_shortened: bool,
    /// Taille estimée de l'enregistrement (fusion comprise).
    pub estimated_bytes: u64,
    /// Place qui manquerait pour tous les enregistrements programmés sur ce
    /// disque ; 0 si tout tient.
    pub missing_bytes: u64,
}

/// État d'un enregistrement.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordingState {
    Scheduled,
    Recording,
    /// Terminé, y compris arrêté à la main.
    Done,
    /// Fichier gardé mais il manque des morceaux (voir le problème).
    Incomplete,
    /// Aucun fichier utilisable.
    Failed,
    /// Jamais commencé (appareil éteint, alarme refusée…).
    Missed,
}

impl RecordingState {
    fn key(self) -> &'static str {
        match self {
            RecordingState::Scheduled => "scheduled",
            RecordingState::Recording => "recording",
            RecordingState::Done => "done",
            RecordingState::Incomplete => "incomplete",
            RecordingState::Failed => "failed",
            RecordingState::Missed => "missed",
        }
    }

    fn from_key(key: &str) -> Option<Self> {
        Some(match key {
            "scheduled" => RecordingState::Scheduled,
            "recording" => RecordingState::Recording,
            "done" => RecordingState::Done,
            "incomplete" => RecordingState::Incomplete,
            "failed" => RecordingState::Failed,
            "missed" => RecordingState::Missed,
            _ => return None,
        })
    }

    /// Encore à venir ou en cours : compte dans le planning.
    pub fn is_planned(self) -> bool {
        matches!(self, RecordingState::Scheduled | RecordingState::Recording)
    }
}

/// Raison d'un enregistrement incomplet, raté ou manqué.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordingProblem {
    /// Coupures de plus d'une minute en tout.
    Gaps,
    /// Réserve du disque atteinte.
    OutOfSpace,
    /// Trop de coupures de suite : abandon.
    ConnectionLost,
    /// Fichier impossible à écrire.
    WriteFailed,
    /// Volume choisi absent (débranché).
    VolumeMissing,
    /// L'app a été arrêtée pendant l'enregistrement.
    Interrupted,
    /// Heure de début passée sans démarrage.
    NotStarted,
}

impl RecordingProblem {
    fn key(self) -> &'static str {
        match self {
            RecordingProblem::Gaps => "gaps",
            RecordingProblem::OutOfSpace => "out_of_space",
            RecordingProblem::ConnectionLost => "connection_lost",
            RecordingProblem::WriteFailed => "write_failed",
            RecordingProblem::VolumeMissing => "volume_missing",
            RecordingProblem::Interrupted => "interrupted",
            RecordingProblem::NotStarted => "not_started",
        }
    }

    fn from_key(key: &str) -> Option<Self> {
        Some(match key {
            "gaps" => RecordingProblem::Gaps,
            "out_of_space" => RecordingProblem::OutOfSpace,
            "connection_lost" => RecordingProblem::ConnectionLost,
            "write_failed" => RecordingProblem::WriteFailed,
            "volume_missing" => RecordingProblem::VolumeMissing,
            "interrupted" => RecordingProblem::Interrupted,
            "not_started" => RecordingProblem::NotStarted,
            _ => return None,
        })
    }
}

/// Un enregistrement, programmé ou fait.
#[derive(Debug, Clone, PartialEq)]
pub struct Recording {
    pub id: i64,
    pub stream_id: i64,
    pub channel: String,
    pub is_adult: bool,
    /// Programmes couverts, dans l'ordre (plusieurs s'ils se suivent).
    pub programmes: Vec<GuideProgramme>,
    /// Fenêtre demandée, marges comprises.
    pub start: i64,
    pub stop: i64,
    pub state: RecordingState,
    pub problem: Option<RecordingProblem>,
    /// Fichier (qu'il existe déjà ou non).
    pub path: PathBuf,
    /// Volume du fichier absent (débranché).
    pub volume_missing: bool,
    pub bytes: u64,
    pub gap_secs: i64,
    pub started_at: Option<i64>,
    pub ended_at: Option<i64>,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub watched: bool,
}

/// Enregistrement à copier maintenant, rendu par [`Recordings::due`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DueRecording {
    pub id: i64,
    pub stream_id: i64,
    /// Fenêtre réelle.
    pub start: i64,
    pub stop: i64,
}

/// Jauge de « Enregistrements ».
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordingUsage {
    /// Place prise par les enregistrements de ce disque.
    pub used_bytes: u64,
    /// Place encore utilisable, réserve déduite.
    pub usable_bytes: u64,
    /// Durée d'enregistrement encore possible, au débit moyen des chaînes.
    pub remaining_secs: i64,
    /// Moins d'une heure possible : notification.
    pub low: bool,
}

/// Planning et liste des enregistrements.
pub struct Recordings {
    conn: Connection,
}

impl Recordings {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        Self::init(Connection::open(path)?)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(mut conn: Connection) -> Result<Self> {
        // Ouverte par l'app et par le service d'enregistrement.
        conn.busy_timeout(std::time::Duration::from_secs(30))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", true)?;
        let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        let tx = conn.transaction()?;
        for (index, sql) in MIGRATIONS.iter().enumerate().skip(version as usize) {
            tx.execute_batch(sql)?;
            tx.pragma_update(None, "user_version", index as i64 + 1)?;
        }
        tx.commit()?;
        Ok(Self { conn })
    }

    // --- Réglages ----------------------------------------------------------

    /// Dossier des prochains enregistrements (disque choisi dans Réglages).
    /// Ceux déjà programmés gardent le leur.
    pub fn set_directory(&self, dir: &Path) -> Result<()> {
        set_setting(&self.conn, DIRECTORY_KEY, &dir.to_string_lossy())
    }

    pub fn directory(&self) -> Result<Option<PathBuf>> {
        Ok(setting(&self.conn, DIRECTORY_KEY)?.map(PathBuf::from))
    }

    /// Dernières marges choisies, sinon 2 min et 5 min.
    pub fn last_margins(&self) -> Result<Margins> {
        let read = |key, default| -> Result<i64> {
            Ok(setting(&self.conn, key)?
                .and_then(|v| v.parse().ok())
                .unwrap_or(default))
        };
        Ok(Margins {
            before_secs: read(MARGIN_BEFORE_KEY, DEFAULT_MARGINS.before_secs)?,
            after_secs: read(MARGIN_AFTER_KEY, DEFAULT_MARGINS.after_secs)?,
        })
    }

    // --- Programmation -----------------------------------------------------

    /// Ce que donnerait [`Self::schedule`] : fusion, marges raccourcies,
    /// taille estimée et place qui manquerait sur `space` (le disque choisi,
    /// voir [`volume_space`]). Rien n'est enregistré.
    pub fn preview(
        &mut self,
        request: &ScheduleRequest,
        space: VolumeSpace,
        now: i64,
    ) -> Result<SchedulePreview> {
        let tx = self.conn.transaction()?;
        let (id, merged) = insert(&tx, request, now)?;
        let preview = describe(&tx, id, merged, space, now)?;
        tx.rollback()?;
        Ok(preview)
    }

    /// Programme l'enregistrement d'un programme du guide, même si la place
    /// manque (l'app a prévenu avec [`Self::preview`]). Les marges choisies
    /// deviennent celles proposées la prochaine fois. Renvoie l'identifiant de
    /// l'enregistrement, qui peut être un enregistrement existant prolongé.
    ///
    /// Pendant une copie, la fenêtre de celle-ci peut changer (fusion,
    /// marge raccourcie) : l'app relit [`Self::due`] et passe sa fin à
    /// [`CaptureControl::set_until`]. De même après [`Self::cancel`].
    pub fn schedule(&mut self, request: &ScheduleRequest, now: i64) -> Result<i64> {
        let tx = self.conn.transaction()?;
        let (id, _) = insert(&tx, request, now)?;
        set_setting(
            &tx,
            MARGIN_BEFORE_KEY,
            &clamp_margin(request.margins.before_secs).to_string(),
        )?;
        set_setting(
            &tx,
            MARGIN_AFTER_KEY,
            &clamp_margin(request.margins.after_secs).to_string(),
        )?;
        tx.commit()?;
        Ok(id)
    }

    /// Annule un enregistrement programmé. Un enregistrement en cours
    /// s'arrête avec [`CaptureControl::stop`], puis [`Self::finish`].
    pub fn cancel(&mut self, id: i64) -> Result<()> {
        let deleted = self.conn.execute(
            "DELETE FROM recording WHERE id = ?1 AND state = 'scheduled'",
            [id],
        )?;
        if deleted == 0 {
            return Err(Error::RecordingNotFound);
        }
        Ok(())
    }

    /// Déconnexion ou changement d'identifiant : les programmations sont
    /// annulées, les fichiers restent.
    pub fn cancel_all_scheduled(&mut self) -> Result<usize> {
        Ok(self
            .conn
            .execute("DELETE FROM recording WHERE state = 'scheduled'", [])?)
    }

    // --- Démarrage et fin --------------------------------------------------

    /// Heure de la prochaine alarme : début réel du prochain enregistrement
    /// programmé (déjà passé si l'un doit démarrer maintenant).
    pub fn next_start(&self, now: i64) -> Result<Option<i64>> {
        Ok(self
            .slots()?
            .into_iter()
            .filter(|(r, slot)| r.state == RecordingState::Scheduled && slot.stop > now)
            .map(|(_, slot)| slot.start)
            .min())
    }

    /// Enregistrement à copier maintenant : dans sa fenêtre réelle, programmé
    /// ou en cours (reprise après un arrêt de l'app).
    pub fn due(&self, now: i64) -> Result<Option<DueRecording>> {
        Ok(self
            .slots()?
            .into_iter()
            .filter(|(r, slot)| r.state.is_planned() && slot.start <= now && now < slot.stop)
            .min_by_key(|(_, slot)| slot.start)
            .map(|(r, slot)| DueRecording {
                id: r.id,
                stream_id: r.stream_id,
                start: slot.start,
                stop: slot.stop,
            }))
    }

    /// Démarre (ou reprend) la copie d'un enregistrement dû. La place est
    /// revérifiée sur `space` (`None` : volume absent) : si la réserve est
    /// déjà atteinte, ou le volume débranché, l'enregistrement est raté et
    /// l'erreur dit pourquoi.
    pub fn begin(&mut self, id: i64, space: Option<VolumeSpace>, now: i64) -> Result<CaptureJob> {
        let due = self
            .due(now)?
            .filter(|d| d.id == id)
            .ok_or(Error::RecordingNotFound)?;
        let recording = self.recording(id)?.ok_or(Error::RecordingNotFound)?;
        // Reprise impossible : ce qui est déjà copié reste lisible.
        let has_data = std::fs::metadata(&recording.path).is_ok_and(|m| m.len() > 0);
        let fail = |this: &mut Self, problem: RecordingProblem, error: Error| {
            let state = if has_data {
                RecordingState::Incomplete
            } else {
                RecordingState::Failed
            };
            this.set_end(id, state, Some(problem), now)?;
            Err(error)
        };
        let Some(space) = space else {
            return fail(self, RecordingProblem::VolumeMissing, Error::VolumeMissing);
        };
        if space.usable_bytes() == 0 {
            return fail(self, RecordingProblem::OutOfSpace, Error::NotEnoughSpace);
        }
        if let Some(dir) = recording.path.parent()
            && std::fs::create_dir_all(dir).is_err()
        {
            return fail(self, RecordingProblem::VolumeMissing, Error::VolumeMissing);
        }
        // Le temps perdu est un trou : alarme en retard (réglage de batterie
        // du fabricant…), ou app arrêtée pendant la copie.
        let gap = match (recording.state, self.progress_at(id)?) {
            (RecordingState::Recording, Some(last)) => (now - last).max(0),
            _ => (now - due.start).max(0),
        };
        self.conn.execute(
            "UPDATE recording SET state = 'recording', problem = NULL,
                 started_at = COALESCE(started_at, ?2), progress_at = ?2,
                 gap_secs = gap_secs + ?3
             WHERE id = ?1",
            params![id, now, gap],
        )?;
        Ok(CaptureJob {
            recording_id: id,
            stream_id: recording.stream_id,
            path: recording.path,
            until: due.stop,
        })
    }

    /// Avancement d'une copie en cours (taille affichée, reprise après un
    /// arrêt de l'app). À appeler de temps en temps, par exemple chaque
    /// minute.
    pub fn update_progress(&mut self, id: i64, bytes: u64, now: i64) -> Result<()> {
        self.conn.execute(
            "UPDATE recording SET bytes = ?2, progress_at = ?3
             WHERE id = ?1 AND state = 'recording'",
            params![id, bytes as i64, now],
        )?;
        Ok(())
    }

    /// Enregistre le bilan d'une copie et renvoie l'enregistrement à jour
    /// (pour la notification « terminé » ou « problème »). Le débit mesuré
    /// améliore l'estimation de la chaîne.
    pub fn finish(&mut self, id: i64, report: &CaptureReport, now: i64) -> Result<Recording> {
        let recording = self.recording(id)?.ok_or(Error::RecordingNotFound)?;
        let gap = recording.gap_secs + report.gap_secs;
        let (state, problem) = match report.end {
            // Arrêté à la main : fait, sans problème à signaler.
            CaptureEnd::Stopped => (RecordingState::Done, None),
            _ if report.bytes == 0 => (
                RecordingState::Failed,
                Some(match report.end {
                    CaptureEnd::OutOfSpace => RecordingProblem::OutOfSpace,
                    CaptureEnd::WriteFailed => RecordingProblem::WriteFailed,
                    _ => RecordingProblem::ConnectionLost,
                }),
            ),
            CaptureEnd::OutOfSpace => (
                RecordingState::Incomplete,
                Some(RecordingProblem::OutOfSpace),
            ),
            CaptureEnd::WriteFailed => (
                RecordingState::Incomplete,
                Some(RecordingProblem::WriteFailed),
            ),
            CaptureEnd::ConnectionLost => (
                RecordingState::Incomplete,
                Some(RecordingProblem::ConnectionLost),
            ),
            CaptureEnd::Finished if gap > MAX_GAP_SECS => {
                (RecordingState::Incomplete, Some(RecordingProblem::Gaps))
            }
            CaptureEnd::Finished => (RecordingState::Done, None),
        };
        let tx = self.conn.transaction()?;
        tx.execute(
            "UPDATE recording SET state = ?2, problem = ?3, bytes = ?4, gap_secs = ?5,
                 ended_at = ?6, progress_at = ?6
             WHERE id = ?1",
            params![
                id,
                state.key(),
                problem.map(RecordingProblem::key),
                report.bytes as i64,
                gap,
                now
            ],
        )?;
        learn_rate(
            &tx,
            recording.stream_id,
            report.written,
            report.received_secs as f64,
        )?;
        tx.commit()?;
        self.recording(id)?.ok_or(Error::RecordingNotFound)
    }

    /// Au lancement de l'app ou du service, sans copie en cours : marque
    /// manqués les enregistrements jamais commencés dont la fenêtre est
    /// passée, et incomplets ceux interrompus par un arrêt de l'app (ceux
    /// encore dans leur fenêtre restent dus et reprennent avec
    /// [`Self::begin`]). Renvoie les enregistrements à signaler.
    pub fn recover(&mut self, now: i64) -> Result<Vec<Recording>> {
        let ended: Vec<(Recording, RecordingState, RecordingProblem)> = self
            .slots()?
            .into_iter()
            .filter(|(r, slot)| r.state.is_planned() && slot.stop <= now)
            .map(|(r, _)| {
                let (state, problem) = match r.state {
                    RecordingState::Recording if r.bytes > 0 => {
                        (RecordingState::Incomplete, RecordingProblem::Interrupted)
                    }
                    RecordingState::Recording => {
                        (RecordingState::Failed, RecordingProblem::Interrupted)
                    }
                    _ => (RecordingState::Missed, RecordingProblem::NotStarted),
                };
                (r, state, problem)
            })
            .collect();
        let mut changed = Vec::with_capacity(ended.len());
        for (recording, state, problem) in ended {
            // La taille réelle du fichier, que la dernière mesure a pu manquer.
            if let Ok(meta) = std::fs::metadata(&recording.path) {
                self.conn.execute(
                    "UPDATE recording SET bytes = ?2 WHERE id = ?1",
                    params![recording.id, meta.len() as i64],
                )?;
            }
            let ended_at = recording.ended_at.or(self.progress_at(recording.id)?);
            self.set_end(recording.id, state, Some(problem), ended_at.unwrap_or(now))?;
            if let Some(r) = self.recording(recording.id)? {
                changed.push(r);
            }
        }
        Ok(changed)
    }

    // --- Liste -------------------------------------------------------------

    /// Un enregistrement, quel que soit le contrôle parental.
    pub fn recording(&self, id: i64) -> Result<Option<Recording>> {
        let recording = self
            .conn
            .query_row(&format!("{SELECT} WHERE id = ?1"), [id], row_to_recording)
            .optional()?
            .flatten();
        recording.map(|r| self.with_programmes(r)).transpose()
    }

    /// Enregistrements à venir ou en cours, par heure de début. Les adultes
    /// n'y sont que si le contrôle parental est déverrouillé.
    pub fn scheduled(&self, parental_unlocked: bool) -> Result<Vec<Recording>> {
        self.list(
            "state IN ('scheduled', 'recording')",
            "start",
            parental_unlocked,
        )
    }

    /// Enregistrements faits (y compris incomplets, ratés et manqués), les
    /// plus récents d'abord. Les adultes n'y sont que si le contrôle
    /// parental est déverrouillé.
    pub fn recorded(&self, parental_unlocked: bool) -> Result<Vec<Recording>> {
        self.list(
            "state NOT IN ('scheduled', 'recording')",
            "start DESC",
            parental_unlocked,
        )
    }

    /// Supprime un enregistrement fait et son fichier.
    pub fn delete(&mut self, id: i64) -> Result<()> {
        let recording = self.recording(id)?.ok_or(Error::RecordingNotFound)?;
        if recording.state.is_planned() {
            return Err(Error::RecordingNotFound);
        }
        // Volume débranché : le fichier est encore sur la clé, l'entrée reste
        // pour pouvoir le supprimer une fois la clé rebranchée.
        if recording.volume_missing {
            return Err(Error::VolumeMissing);
        }
        match std::fs::remove_file(&recording.path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(Error::Storage(e.kind().to_string())),
        }
        self.conn
            .execute("DELETE FROM recording WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Position de lecture d'un enregistrement ; vu au-delà de 95 %.
    pub fn set_position(&mut self, id: i64, position_ms: i64, duration_ms: i64) -> Result<()> {
        let watched = duration_ms > 0 && position_ms as f64 >= duration_ms as f64 * WATCHED_RATIO;
        let updated = self.conn.execute(
            "UPDATE recording SET position_ms = ?2, duration_ms = ?3,
                 watched = MAX(watched, ?4)
             WHERE id = ?1",
            params![id, position_ms.max(0), duration_ms.max(0), watched],
        )?;
        if updated == 0 {
            return Err(Error::RecordingNotFound);
        }
        Ok(())
    }

    // --- Espace ------------------------------------------------------------

    /// Jauge du disque `dir` : place prise, place utilisable et durée encore
    /// possible au débit moyen des chaînes déjà mesurées.
    pub fn usage(&self, dir: &Path, space: VolumeSpace) -> Result<RecordingUsage> {
        let dir = dir.to_string_lossy();
        let used: i64 = self.conn.query_row(
            "SELECT COALESCE(SUM(bytes), 0) FROM recording WHERE dir = ?1",
            [dir.as_ref()],
            |r| r.get(0),
        )?;
        let rate: Option<f64> =
            self.conn
                .query_row("SELECT AVG(bytes_per_sec) FROM channel_rate", [], |r| {
                    r.get(0)
                })?;
        let rate = rate.unwrap_or_else(space::default_bytes_per_sec).max(1.0);
        let usable = space.usable_bytes();
        let remaining_secs = (usable as f64 / rate) as i64;
        Ok(RecordingUsage {
            used_bytes: used.max(0) as u64,
            usable_bytes: usable,
            remaining_secs,
            low: remaining_secs < LOW_SPACE_SECS,
        })
    }

    /// Enregistrements à proposer pour libérer `missing_bytes` sur le disque
    /// `dir` : déjà vus d'abord, puis les plus anciens. Rien n'est supprimé.
    /// Les adultes n'y sont que si le contrôle parental est déverrouillé.
    pub fn cleanup_candidates(
        &self,
        dir: &Path,
        missing_bytes: u64,
        parental_unlocked: bool,
    ) -> Result<Vec<Recording>> {
        let adult = if parental_unlocked {
            ""
        } else {
            " AND is_adult = 0"
        };
        let mut stmt = self.conn.prepare(&format!(
            "{SELECT} WHERE dir = ?1 AND state NOT IN ('scheduled', 'recording') AND bytes > 0
             {adult} ORDER BY watched DESC, start"
        ))?;
        let rows = stmt.query_map([dir.to_string_lossy().as_ref()], row_to_recording)?;
        let mut freed = 0u64;
        let mut picked = Vec::new();
        for row in rows {
            if freed >= missing_bytes {
                break;
            }
            let Some(recording) = row? else { continue };
            freed += recording.bytes;
            picked.push(self.with_programmes(recording)?);
        }
        Ok(picked)
    }

    /// Débit mesuré d'une chaîne pendant l'aperçu en Direct : améliore
    /// l'estimation de ses enregistrements. Une mesure de moins de 30 s
    /// est ignorée.
    pub fn learn_rate(&mut self, stream_id: i64, bytes: u64, secs: f64) -> Result<()> {
        learn_rate(&self.conn, stream_id, bytes, secs)
    }

    /// Débit estimé d'une chaîne, en octets par seconde.
    pub fn bytes_per_sec(&self, stream_id: i64) -> Result<f64> {
        rate(&self.conn, stream_id)
    }

    // --- Interne -----------------------------------------------------------

    fn list(
        &self,
        condition: &str,
        order: &str,
        parental_unlocked: bool,
    ) -> Result<Vec<Recording>> {
        let adult = if parental_unlocked {
            ""
        } else {
            " AND is_adult = 0"
        };
        let mut stmt = self.conn.prepare(&format!(
            "{SELECT} WHERE {condition}{adult} ORDER BY {order}, id"
        ))?;
        let rows = stmt.query_map([], row_to_recording)?;
        let mut list = Vec::new();
        for row in rows {
            // Ligne illisible (état inconnu d'une version future) : ignorée.
            if let Some(recording) = row? {
                list.push(self.with_programmes(recording)?);
            }
        }
        Ok(list)
    }

    fn with_programmes(&self, mut recording: Recording) -> Result<Recording> {
        recording.programmes = programmes(&self.conn, recording.id)?;
        Ok(recording)
    }

    /// Enregistrements du planning et leur fenêtre réelle.
    fn slots(&self) -> Result<Vec<(Recording, plan::Slot)>> {
        let planned = self.list("state IN ('scheduled', 'recording')", "start", true)?;
        let windows = slots(&planned.iter().map(to_planned).collect::<Vec<_>>());
        Ok(planned
            .into_iter()
            .filter_map(|r| {
                let slot = *windows.iter().find(|s| s.id == r.id)?;
                Some((r, slot))
            })
            .collect())
    }

    fn progress_at(&self, id: i64) -> Result<Option<i64>> {
        Ok(self
            .conn
            .query_row(
                "SELECT progress_at FROM recording WHERE id = ?1",
                [id],
                |r| r.get(0),
            )
            .optional()?
            .flatten())
    }

    fn set_end(
        &mut self,
        id: i64,
        state: RecordingState,
        problem: Option<RecordingProblem>,
        now: i64,
    ) -> Result<()> {
        self.conn.execute(
            "UPDATE recording SET state = ?2, problem = ?3, ended_at = ?4 WHERE id = ?1",
            params![id, state.key(), problem.map(RecordingProblem::key), now],
        )?;
        Ok(())
    }
}

const SELECT: &str = "SELECT id, stream_id, channel, is_adult, start, stop, state, problem, dir,
    bytes, gap_secs, started_at, ended_at, position_ms, duration_ms, watched FROM recording";

/// Ligne de `recording` ; `None` si son état est inconnu.
fn row_to_recording(row: &rusqlite::Row) -> rusqlite::Result<Option<Recording>> {
    let state: String = row.get(6)?;
    let Some(state) = RecordingState::from_key(&state) else {
        return Ok(None);
    };
    let id: i64 = row.get(0)?;
    let dir: String = row.get(8)?;
    let dir = PathBuf::from(dir);
    let problem: Option<String> = row.get(7)?;
    Ok(Some(Recording {
        id,
        stream_id: row.get(1)?,
        channel: row.get(2)?,
        is_adult: row.get(3)?,
        programmes: Vec::new(),
        start: row.get(4)?,
        stop: row.get(5)?,
        state,
        problem: problem.as_deref().and_then(RecordingProblem::from_key),
        volume_missing: !dir.is_dir(),
        path: file_path(&dir, id),
        bytes: row.get::<_, i64>(9)?.max(0) as u64,
        gap_secs: row.get(10)?,
        started_at: row.get(11)?,
        ended_at: row.get(12)?,
        position_ms: row.get(13)?,
        duration_ms: row.get(14)?,
        watched: row.get(15)?,
    }))
}

/// Fichier d'un enregistrement : `{dossier}/{id}.ts`. Le titre n'y figure
/// pas (caractères interdits selon le système de fichiers).
fn file_path(dir: &Path, id: i64) -> PathBuf {
    dir.join(format!("{id}.ts"))
}

fn programmes(conn: &Connection, id: i64) -> Result<Vec<GuideProgramme>> {
    let mut stmt = conn.prepare(
        "SELECT title, description, start, stop FROM programme
         WHERE recording_id = ?1 ORDER BY start",
    )?;
    let rows = stmt.query_map([id], |r| {
        Ok(GuideProgramme {
            title: r.get(0)?,
            description: r.get(1)?,
            start: r.get(2)?,
            stop: r.get(3)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<_>>()?)
}

fn to_planned(r: &Recording) -> Planned {
    Planned {
        id: r.id,
        stream_id: r.stream_id,
        programme_start: r.programmes.first().map_or(r.start, |p| p.start),
        programme_stop: r.programmes.last().map_or(r.stop, |p| p.stop),
        start: r.start,
        stop: r.stop,
    }
}

fn clamp_margin(secs: i64) -> i64 {
    secs.clamp(0, MAX_MARGIN_SECS)
}

/// Ajoute le programme demandé au planning, dans la transaction : nouvel
/// enregistrement, ou fusion avec ceux de la même chaîne qu'il touche.
/// Refuse un programme fini ou qui chevauche un enregistrement d'une autre
/// chaîne. Renvoie l'identifiant et vrai s'il y a eu fusion.
fn insert(tx: &Transaction, request: &ScheduleRequest, now: i64) -> Result<(i64, bool)> {
    let p = &request.programme;
    if p.stop <= p.start {
        return Err(Error::InvalidResponse);
    }
    if p.stop <= now {
        return Err(Error::RecordingEnded);
    }
    let before = clamp_margin(request.margins.before_secs);
    let after = clamp_margin(request.margins.after_secs);
    let new = Planned {
        id: 0,
        stream_id: request.stream_id,
        programme_start: p.start,
        programme_stop: p.stop,
        start: p.start - before,
        stop: p.stop + after,
    };
    let planned = planned(tx)?;
    if let Some(conflict) = planned
        .iter()
        .find(|o| o.stream_id != new.stream_id && programmes_overlap(o, &new))
    {
        return Err(Error::RecordingConflict {
            recording_id: conflict.id,
        });
    }
    let mut merged: Vec<&Planned> = planned
        .iter()
        .filter(|o| o.stream_id == new.stream_id && windows_touch(o, &new))
        .collect();
    merged.sort_by_key(|o| o.start);
    // Pas de fusion si l'enregistrement continu engloberait un programme
    // d'une autre chaîne : chacun garde le sien, marges partagées.
    let span = merged.iter().fold(new, |mut span, o| {
        span.programme_start = span.programme_start.min(o.programme_start);
        span.programme_stop = span.programme_stop.max(o.programme_stop);
        span
    });
    if planned
        .iter()
        .any(|o| o.stream_id != new.stream_id && programmes_overlap(o, &span))
    {
        merged.clear();
    }
    let (start, stop) = merged.iter().fold((new.start, new.stop), |(a, b), o| {
        (a.min(o.start), b.max(o.stop))
    });
    // Programmé pendant le programme : la copie commence tout de suite.
    let start = start.max(now);
    let id = match merged.first() {
        Some(first) => {
            // Le premier garde son identifiant (et son fichier s'il est en
            // cours) ; les autres lui donnent leurs programmes.
            let others: Vec<i64> = merged.iter().skip(1).map(|o| o.id).collect();
            for other in &others {
                if state_of(tx, *other)? == Some(RecordingState::Recording) {
                    // Deux copies de la même chaîne ne peuvent pas fusionner.
                    return Err(Error::RecordingConflict {
                        recording_id: *other,
                    });
                }
                tx.execute(
                    "UPDATE programme SET recording_id = ?1 WHERE recording_id = ?2",
                    params![first.id, other],
                )?;
                tx.execute("DELETE FROM recording WHERE id = ?1", [other])?;
            }
            tx.execute(
                "UPDATE recording SET start = ?2, stop = ?3,
                     is_adult = MAX(is_adult, ?4) WHERE id = ?1",
                params![first.id, start.min(first.start), stop, request.is_adult],
            )?;
            first.id
        }
        None => {
            let dir = setting(tx, DIRECTORY_KEY)?.ok_or(Error::VolumeMissing)?;
            tx.execute(
                "INSERT INTO recording (stream_id, channel, is_adult, start, stop, state, dir,
                     created_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, 'scheduled', ?6, ?7)",
                params![
                    request.stream_id,
                    request.channel,
                    request.is_adult,
                    start,
                    stop,
                    dir,
                    now
                ],
            )?;
            tx.last_insert_rowid()
        }
    };
    tx.execute(
        "INSERT OR REPLACE INTO programme (recording_id, start, stop, title, description)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![id, p.start, p.stop, p.title, p.description],
    )?;
    Ok((id, !merged.is_empty()))
}

/// Aperçu de l'enregistrement `id` dans le planning de la transaction.
fn describe(
    tx: &Transaction,
    id: i64,
    merged: bool,
    space: VolumeSpace,
    now: i64,
) -> Result<SchedulePreview> {
    let planned = planned(tx)?;
    let windows = slots(&planned);
    let this = planned
        .iter()
        .find(|p| p.id == id)
        .ok_or(Error::RecordingNotFound)?;
    let slot = windows
        .iter()
        .find(|s| s.id == id)
        .ok_or(Error::RecordingNotFound)?;
    let dir: String = tx.query_row("SELECT dir FROM recording WHERE id = ?1", [id], |r| {
        r.get(0)
    })?;
    // Place nécessaire : la suite de tous les enregistrements de ce disque.
    let mut needed = 0u64;
    let mut estimated = 0u64;
    for p in &planned {
        let Some(s) = windows.iter().find(|s| s.id == p.id) else {
            continue;
        };
        let same_dir: bool = tx.query_row(
            "SELECT dir = ?2 FROM recording WHERE id = ?1",
            params![p.id, dir],
            |r| r.get(0),
        )?;
        let bytes = space::estimate(rate(tx, p.stream_id)?, s.stop - s.start.max(now));
        if p.id == id {
            estimated = space::estimate(rate(tx, p.stream_id)?, s.stop - s.start);
        }
        if same_dir {
            needed += bytes;
        }
    }
    Ok(SchedulePreview {
        merged_into: merged.then_some(id),
        start: slot.start,
        stop: slot.stop,
        margins_shortened: slot.start > this.start || slot.stop < this.stop,
        estimated_bytes: estimated,
        missing_bytes: needed.saturating_sub(space.usable_bytes()),
    })
}

fn planned(conn: &Connection) -> Result<Vec<Planned>> {
    let mut stmt = conn.prepare(
        "SELECT r.id, r.stream_id, MIN(p.start), MAX(p.stop), r.start, r.stop
         FROM recording r JOIN programme p ON p.recording_id = r.id
         WHERE r.state IN ('scheduled', 'recording')
         GROUP BY r.id",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok(Planned {
            id: r.get(0)?,
            stream_id: r.get(1)?,
            programme_start: r.get(2)?,
            programme_stop: r.get(3)?,
            start: r.get(4)?,
            stop: r.get(5)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<_>>()?)
}

fn state_of(conn: &Connection, id: i64) -> Result<Option<RecordingState>> {
    let state: Option<String> = conn
        .query_row("SELECT state FROM recording WHERE id = ?1", [id], |r| {
            r.get(0)
        })
        .optional()?;
    Ok(state.as_deref().and_then(RecordingState::from_key))
}

fn rate(conn: &Connection, stream_id: i64) -> Result<f64> {
    let rate: Option<f64> = conn
        .query_row(
            "SELECT bytes_per_sec FROM channel_rate WHERE stream_id = ?1",
            [stream_id],
            |r| r.get(0),
        )
        .optional()?;
    Ok(rate
        .filter(|r| r.is_finite() && *r > 0.0)
        .unwrap_or_else(space::default_bytes_per_sec))
}

/// Moyenne pondérée par la durée des mesures, l'historique plafonné à 3 h
/// pour suivre un changement de qualité de la chaîne.
fn learn_rate(conn: &Connection, stream_id: i64, bytes: u64, secs: f64) -> Result<()> {
    if !secs.is_finite() || secs < MIN_RATE_SAMPLE_SECS || bytes == 0 {
        return Ok(());
    }
    let sample = bytes as f64 / secs;
    let previous: Option<(f64, f64)> = conn
        .query_row(
            "SELECT bytes_per_sec, weight_secs FROM channel_rate WHERE stream_id = ?1",
            [stream_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let (rate, weight) = match previous {
        Some((rate, weight)) if rate.is_finite() && weight.is_finite() && weight > 0.0 => {
            let weight = weight.min(MAX_RATE_WEIGHT_SECS);
            (
                (rate * weight + sample * secs) / (weight + secs),
                weight + secs,
            )
        }
        _ => (sample, secs),
    };
    conn.execute(
        "INSERT OR REPLACE INTO channel_rate (stream_id, bytes_per_sec, weight_secs)
         VALUES (?1, ?2, ?3)",
        params![stream_id, rate, weight.min(MAX_RATE_WEIGHT_SECS)],
    )?;
    Ok(())
}

fn setting(conn: &Connection, key: &str) -> Result<Option<String>> {
    Ok(conn
        .query_row("SELECT value FROM setting WHERE key = ?1", [key], |r| {
            r.get(0)
        })
        .optional()?)
}

fn set_setting(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT OR REPLACE INTO setting (key, value) VALUES (?1, ?2)",
        params![key, value],
    )?;
    Ok(())
}

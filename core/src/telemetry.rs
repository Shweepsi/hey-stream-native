//! Envoi des données d'usage de l'APK testeur (`docs/DECISIONS.md`,
//! « Données des testeurs »).
//!
//! L'app écrit deux sortes de fichiers sur l'appareil :
//!
//! - les réponses du panneau, nettoyées (voir [`crate::recording`], mode
//!   complet), un fichier `.json` par réponse ;
//! - ses événements (usage, lecture, erreurs, appareil), en lignes JSON dans
//!   des fichiers `.jsonl` (un fichier en cours d'écriture porte une autre
//!   extension et n'est pas envoyé).
//!
//! [`upload_telemetry`] demande d'abord au service si la collecte est active
//! (coupure à distance), puis envoie les fichiers par lots compressés (gzip,
//! une ligne JSON par réponse ou par événement) et efface chaque lot accepté.
//! Aucune requête au panneau : seulement au service de collecte.

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::Duration;

use flate2::Compression;
use flate2::write::GzEncoder;
use serde::Deserialize;

use crate::client::{check_status, http_builder};
use crate::error::{Error, Result};

/// Taille d'un lot avant compression (un fichier plus gros part seul).
const BATCH_BYTES: u64 = 4 * 1024 * 1024;
/// Données envoyées au plus par appel, avant compression : le reste part au
/// suivant.
const RUN_BYTES: u64 = 64 * 1024 * 1024;
/// Réponse du service quand la collecte est coupée.
const PAUSED: u16 = 423;
/// Réponse du service à un lot trop gros.
const TOO_LARGE: u16 = 413;

/// Service de collecte et identité de l'appareil.
#[derive(Debug, Clone)]
pub struct TelemetryTarget {
    /// `https://….workers.dev`, sans `/` final.
    pub endpoint: String,
    /// Clé d'envoi (elle ne permet pas de lire).
    pub key: String,
    /// Numéro aléatoire de l'appareil, sans lien avec la personne.
    pub device: String,
    pub app_version: String,
}

/// Résultat d'un envoi.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UploadOutcome {
    /// `files` fichiers envoyés et effacés, `bytes` octets avant compression.
    Sent { files: u32, bytes: u64 },
    /// La collecte est coupée (tout l'appareil ou tous) : rien n'est envoyé.
    Paused,
}

/// Sorte de données, et son dossier.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TelemetryKind {
    Responses,
    Events,
}

impl TelemetryKind {
    fn name(self) -> &'static str {
        match self {
            Self::Responses => "responses",
            Self::Events => "events",
        }
    }

    fn extension(self) -> &'static str {
        match self {
            Self::Responses => "json",
            Self::Events => "jsonl",
        }
    }
}

#[derive(Deserialize)]
struct Status {
    #[serde(default)]
    collect: bool,
}

/// Envoie les fichiers de `dirs`, dans l'ordre, puis les efface. Pour chaque
/// sorte, s'arrête au premier lot en échec (ses fichiers restent pour le
/// prochain essai) et passe à la suivante. Une erreur n'est rendue que si
/// rien n'est parti.
pub async fn upload_telemetry(
    target: &TelemetryTarget,
    dirs: &[(TelemetryKind, PathBuf)],
) -> Result<UploadOutcome> {
    let http = http_builder()
        .connect_timeout(Duration::from_secs(20))
        // Un lot de réponses entières peut peser plusieurs Mo compressés
        // (liste de films complète) : liaison lente de la Mi Box comprise.
        .timeout(Duration::from_secs(900))
        .build()
        .map_err(Error::network)?;
    let endpoint = target.endpoint.trim_end_matches('/');
    let headers = |request: reqwest::RequestBuilder| {
        request
            .bearer_auth(&target.key)
            .header("X-Device", &target.device)
            .header("X-App-Version", &target.app_version)
    };

    let status = headers(http.get(format!("{endpoint}/v1/status")))
        .send()
        .await
        .map_err(Error::network)?;
    if status.status().as_u16() == PAUSED {
        return Ok(UploadOutcome::Paused);
    }
    check_status(status.status().as_u16())?;
    let body = status.bytes().await.map_err(Error::network)?;
    let status: Status = serde_json::from_slice(&body).map_err(|_| Error::InvalidResponse)?;
    if !status.collect {
        return Ok(UploadOutcome::Paused);
    }

    // Chaque sorte a son budget et ses erreurs : un lot de réponses qui
    // échoue toujours (trop lent, refusé) ne retient jamais les événements.
    let (mut files, mut bytes) = (0u32, 0u64);
    let mut failure = None;
    for (kind, dir) in dirs {
        let mut sent = 0u64;
        for batch in batches(dir, kind.extension()) {
            if sent >= RUN_BYTES {
                break;
            }
            let bundle = bundle(&batch);
            // Fichiers vides : rien à envoyer.
            for file in &bundle.empty {
                let _ = fs::remove_file(file);
            }
            let Some(body) = bundle.body else {
                continue;
            };
            let response = headers(http.post(format!("{endpoint}/v1/ingest")))
                .header("X-Kind", kind.name())
                .header(reqwest::header::CONTENT_TYPE, "application/gzip")
                .body(body)
                .send()
                .await;
            let code = match response {
                Ok(response) => response.status().as_u16(),
                Err(error) => {
                    failure = Some(Error::network(error));
                    break;
                }
            };
            match code {
                PAUSED => return Ok(UploadOutcome::Paused),
                // Trop gros pour le service : ne partira jamais.
                TOO_LARGE => {}
                code => {
                    if let Err(error) = check_status(code) {
                        failure = Some(error);
                        break;
                    }
                    files += bundle.sent.len() as u32;
                    bytes += bundle.raw;
                    sent += bundle.raw;
                }
            }
            // Seulement ceux qui sont partis ; un fichier illisible reste.
            for file in &bundle.sent {
                let _ = fs::remove_file(file);
            }
        }
    }
    match failure {
        Some(error) if files == 0 => Err(error),
        _ => Ok(UploadOutcome::Sent { files, bytes }),
    }
}

/// Fichiers `*.extension` de `dir`, du plus ancien au plus récent, groupés
/// par lots de [`BATCH_BYTES`] environ.
fn batches(dir: &Path, extension: &str) -> Vec<Vec<PathBuf>> {
    let mut files: Vec<(PathBuf, u64)> = files_with(dir, extension)
        .into_iter()
        .filter_map(|p| fs::metadata(&p).ok().map(|m| (p, m.len())))
        .collect();
    files.sort_by(|(a, _), (b, _)| a.file_name().cmp(&b.file_name()));
    let mut batches: Vec<Vec<PathBuf>> = Vec::new();
    let mut size = 0;
    for (file, len) in files {
        match batches.last_mut() {
            Some(batch) if size + len <= BATCH_BYTES => batch.push(file),
            _ => {
                batches.push(vec![file]);
                size = 0;
            }
        }
        size += len;
    }
    batches
}

/// Un lot prêt à partir.
struct Bundle {
    /// Lignes JSON compressées ; `None` si aucun fichier n'a pu être lu.
    body: Option<Vec<u8>>,
    /// Fichiers compris dans `body`.
    sent: Vec<PathBuf>,
    /// Fichiers vides, à effacer.
    empty: Vec<PathBuf>,
    /// Taille avant compression.
    raw: u64,
}

/// Les fichiers bout à bout, une ligne JSON par réponse ou par événement,
/// compressés au fil de la lecture (jamais un fichier entier en mémoire). Un
/// fichier illisible est laissé pour le prochain envoi.
fn bundle(files: &[PathBuf]) -> Bundle {
    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    let (mut sent, mut empty, mut raw) = (Vec::new(), Vec::new(), 0u64);
    for file in files {
        let Ok(mut reader) = fs::File::open(file) else {
            continue;
        };
        let mut counted = Counter {
            inner: &mut gz,
            bytes: 0,
            last: b'\n',
        };
        if std::io::copy(&mut reader, &mut counted).is_err() {
            // Lot inutilisable (lecture coupée en plein milieu) : rien ne part.
            return Bundle {
                body: None,
                sent: Vec::new(),
                empty,
                raw: 0,
            };
        }
        let (bytes, last) = (counted.bytes, counted.last);
        if bytes == 0 {
            empty.push(file.clone());
            continue;
        }
        if last != b'\n' && gz.write_all(b"\n").is_err() {
            return Bundle {
                body: None,
                sent: Vec::new(),
                empty,
                raw: 0,
            };
        }
        raw += bytes + u64::from(last != b'\n');
        sent.push(file.clone());
    }
    let body = if sent.is_empty() {
        None
    } else {
        gz.finish().ok()
    };
    if body.is_none() {
        sent.clear();
    }
    Bundle {
        body,
        sent,
        empty,
        raw,
    }
}

/// Compte les octets écrits et retient le dernier.
struct Counter<'a, W: Write> {
    inner: &'a mut W,
    bytes: u64,
    last: u8,
}

impl<W: Write> Write for Counter<'_, W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.inner.write(buf)?;
        if n > 0 {
            self.bytes += n as u64;
            self.last = buf[n - 1];
        }
        Ok(n)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

// --- Journal des événements ----------------------------------------------------

/// Taille d'un fichier d'événements avant d'en ouvrir un autre.
const EVENT_FILE_BYTES: u64 = 512 * 1024;
/// Extension d'un fichier en cours d'écriture (jamais envoyé).
const OPEN_EXTENSION: &str = "open";

/// Journal des événements de l'app : une ligne JSON par événement, dans des
/// fichiers `.open` renommés en `.jsonl` (prêts à partir) quand ils sont
/// pleins, à chaque [`EventLog::rotate`], ou au lancement suivant pour ceux
/// d'un lancement arrêté net.
pub struct EventLog {
    dir: PathBuf,
    /// Numéro du lancement, dans le nom des fichiers.
    launch: String,
    current: Option<PathBuf>,
}

impl EventLog {
    /// Ouvre le journal de `dir` ; les fichiers laissés ouverts par un
    /// lancement précédent deviennent prêts à partir.
    pub fn open(dir: impl Into<PathBuf>, launch: &str) -> Self {
        let dir = dir.into();
        for file in files_with(&dir, OPEN_EXTENSION) {
            let _ = fs::rename(&file, file.with_extension("jsonl"));
        }
        let launch = launch
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
            .collect();
        Self {
            dir,
            launch,
            current: None,
        }
    }

    /// Ajoute `line` (un objet JSON, sans retour à la ligne). Une ligne
    /// invalide est ignorée ; une erreur d'écriture aussi (jamais fatale).
    pub fn append(&mut self, line: &str, now_ms: i64) {
        let line = line.trim();
        if line.contains('\n') || serde_json::from_str::<serde::de::IgnoredAny>(line).is_err() {
            return;
        }
        let file = match &self.current {
            Some(file) => file.clone(),
            None => {
                if fs::create_dir_all(&self.dir).is_err() {
                    return;
                }
                let file = self
                    .dir
                    .join(format!("{now_ms:013}-{}.{OPEN_EXTENSION}", self.launch));
                self.current = Some(file.clone());
                file
            }
        };
        let written = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&file)
            .and_then(|mut f| f.write_all(format!("{line}\n").as_bytes()));
        if written.is_ok() && fs::metadata(&file).is_ok_and(|m| m.len() >= EVENT_FILE_BYTES) {
            self.rotate();
        }
    }

    /// Le fichier en cours devient prêt à partir.
    pub fn rotate(&mut self) {
        if let Some(file) = self.current.take() {
            let _ = fs::rename(&file, file.with_extension("jsonl"));
        }
    }

    /// Efface tout ce qui attend (collecte coupée).
    pub fn clear(&mut self) {
        self.current = None;
        let _ = fs::remove_dir_all(&self.dir);
    }
}

fn files_with(dir: &Path, extension: &str) -> Vec<PathBuf> {
    fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| entry.path())
        .filter(|p| p.extension().is_some_and(|e| e == extension))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("heystream-telemetry-{name}"));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn files_are_grouped_in_order_and_partial_ones_are_left() {
        let dir = temp("batches");
        fs::write(dir.join("2.jsonl"), vec![b'x'; BATCH_BYTES as usize]).unwrap();
        fs::write(dir.join("1.jsonl"), b"a").unwrap();
        fs::write(dir.join("3.jsonl"), b"b").unwrap();
        fs::write(dir.join("4.open"), b"en cours").unwrap();
        let names: Vec<Vec<String>> = batches(&dir, "jsonl")
            .into_iter()
            .map(|b| {
                b.iter()
                    .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
                    .collect()
            })
            .collect();
        assert_eq!(names, [vec!["1.jsonl"], vec!["2.jsonl"], vec!["3.jsonl"]]);
    }

    #[test]
    fn bundle_keeps_unreadable_files_and_drops_empty_ones() {
        let dir = temp("bundle");
        let (a, empty, missing) = (
            dir.join("a.jsonl"),
            dir.join("b.jsonl"),
            dir.join("c.jsonl"),
        );
        fs::write(&a, b"{\"x\":1}").unwrap();
        fs::write(&empty, b"").unwrap();
        let bundle = bundle(&[a.clone(), empty.clone(), missing]);
        assert_eq!(bundle.sent, [a]);
        assert_eq!(bundle.empty, [empty]);
        assert_eq!(bundle.raw, 8);
        let mut out = String::new();
        std::io::Read::read_to_string(
            &mut flate2::read::GzDecoder::new(&bundle.body.unwrap()[..]),
            &mut out,
        )
        .unwrap();
        assert_eq!(out, "{\"x\":1}\n");
    }

    #[test]
    fn event_log_rotates_and_recovers_files_of_a_stopped_launch() {
        let dir = temp("log");
        let mut log = EventLog::open(&dir, "lancement-1");
        log.append(r#"{"type":"screen"}"#, 1);
        log.append("pas du json", 2);
        log.append("{\"a\":1}\n{\"b\":2}", 3);
        // Arrêt net : le fichier reste ouvert, jamais envoyé…
        assert_eq!(files_with(&dir, "open").len(), 1);
        assert!(batches(&dir, "jsonl").is_empty());
        // … jusqu'au lancement suivant.
        let mut next = EventLog::open(&dir, "lancement-2");
        let ready = files_with(&dir, "jsonl");
        assert_eq!(ready.len(), 1);
        assert_eq!(
            fs::read_to_string(&ready[0]).unwrap(),
            "{\"type\":\"screen\"}\n"
        );
        next.append("{}", 4);
        next.rotate();
        assert_eq!(files_with(&dir, "jsonl").len(), 2);
        next.clear();
        assert!(!dir.exists());
    }
}

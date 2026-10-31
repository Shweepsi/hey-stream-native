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

/// Envoie les fichiers de `dirs` puis les efface. Arrête au premier lot
/// refusé (les fichiers restent pour le prochain essai).
pub async fn upload_telemetry(
    target: &TelemetryTarget,
    dirs: &[(TelemetryKind, PathBuf)],
) -> Result<UploadOutcome> {
    let http = http_builder()
        .timeout(Duration::from_secs(120))
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

    let (mut files, mut bytes) = (0u32, 0u64);
    for (kind, dir) in dirs {
        for batch in batches(dir, kind.extension()) {
            if bytes >= RUN_BYTES {
                return Ok(UploadOutcome::Sent { files, bytes });
            }
            let Some((body, raw)) = bundle(&batch) else {
                continue;
            };
            let response = headers(http.post(format!("{endpoint}/v1/ingest")))
                .header("X-Kind", kind.name())
                .header(reqwest::header::CONTENT_TYPE, "application/gzip")
                .body(body)
                .send()
                .await
                .map_err(Error::network)?;
            let code = response.status().as_u16();
            if code == PAUSED {
                return Ok(UploadOutcome::Paused);
            }
            check_status(code)?;
            for file in &batch {
                let _ = fs::remove_file(file);
            }
            files += batch.len() as u32;
            bytes += raw;
        }
    }
    Ok(UploadOutcome::Sent { files, bytes })
}

/// Fichiers `*.extension` de `dir`, du plus ancien au plus récent, groupés
/// par lots de [`BATCH_BYTES`] environ.
fn batches(dir: &Path, extension: &str) -> Vec<Vec<PathBuf>> {
    let mut files: Vec<(PathBuf, u64)> = fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| entry.path())
        .filter(|p| p.extension().is_some_and(|e| e == extension))
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

/// Les fichiers bout à bout, une ligne JSON par réponse ou par événement,
/// compressés ; avec leur taille avant compression. Un fichier illisible est
/// sauté ; `None` si rien n'a pu être lu.
fn bundle(files: &[PathBuf]) -> Option<(Vec<u8>, u64)> {
    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    let mut raw = 0u64;
    for file in files {
        let Ok(content) = fs::read(file) else {
            continue;
        };
        let content = content.trim_ascii_end();
        if content.is_empty() {
            continue;
        }
        gz.write_all(content).ok()?;
        gz.write_all(b"\n").ok()?;
        raw += content.len() as u64 + 1;
    }
    (raw > 0).then(|| gz.finish().ok().map(|body| (body, raw)))?
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
}

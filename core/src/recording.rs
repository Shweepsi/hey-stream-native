//! Enregistrement des réponses du panneau pendant l'usage normal de l'app,
//! pour en faire des réponses simulées dans les tests
//! (`core/tests/fixtures/`).
//!
//! Activé à la main dans les outils de test cachés de l'app : aucune requête
//! en plus, seulement celles que l'app fait déjà. Chaque réponse est nettoyée
//! **avant** d'être écrite sur le disque, un fichier par réponse :
//!
//! - identifiant, mot de passe et adresse du panneau remplacés partout ;
//! - hôte de chaque URL remplacé par `example.invalid`, jetons d'URL
//!   (`token=…`) retirés ;
//! - adresses IP remplacées par `192.0.2.1` (plage de documentation) ;
//! - listes du catalogue réduites à un échantillon réparti sur toute la
//!   liste, avec leur nombre total d'entrées.
//!
//! Le nombre de fichiers est plafonné par type de requête : les plus anciens
//! sont effacés.
//!
//! En mode **complet** (APK testeur, voir [`crate::telemetry`]), les listes
//! sont gardées entières et les plafonds sont dix fois plus hauts : les
//! fichiers sont envoyés puis effacés au fil de l'eau.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::value::RawValue;
use serde_json::{Map, Value, json};

use crate::client::XtreamClient;
use crate::error::{Error, Result};
use crate::lenient::text;

/// Hôte écrit à la place de celui de toute URL.
pub const SCRUBBED_HOST: &str = "example.invalid";
/// Adresse écrite à la place de toute adresse IP.
const SCRUBBED_IP: &str = "192.0.2.1";
/// Version du format du fichier exporté.
const EXPORT_FORMAT: u32 = 2;
/// Entrées gardées par liste du catalogue, réparties sur toute la liste.
const LIST_LIMIT: usize = 300;
/// Longueur gardée d'une réponse qui n'est pas du JSON (page d'erreur…).
const TEXT_LIMIT: usize = 2000;
/// Paramètres d'URL retirés : jetons d'accès de certains panneaux.
const SECRET_PARAMS: [&str; 3] = ["token", "auth", "key"];

/// Listes du catalogue : échantillonnées.
const LIST_ACTIONS: [&str; 6] = [
    "get_live_categories",
    "get_live_streams",
    "get_vod_categories",
    "get_vod_streams",
    "get_series_categories",
    "get_series",
];

/// Fichiers gardés par type de requête (`auth` : authentification).
fn keep(action: &str) -> usize {
    match action {
        "get_short_epg" => 40,
        "get_vod_info" | "get_series_info" => 25,
        "auth" => 5,
        a if LIST_ACTIONS.contains(&a) => 3,
        _ => 10,
    }
}

/// Réponses enregistrées et place occupée.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RecordingSummary {
    pub responses: u32,
    pub bytes: u64,
}

/// Enregistreur d'un client : nettoie et écrit chaque réponse.
pub(crate) struct Recorder {
    dir: PathBuf,
    scrubber: Scrubber,
    seq: AtomicU64,
    /// Listes entières, plafonds ×10 (APK testeur).
    full: bool,
}

impl Recorder {
    pub(crate) fn new(dir: PathBuf, client: &XtreamClient, full: bool) -> Result<Self> {
        fs::create_dir_all(&dir).map_err(storage)?;
        Ok(Self {
            dir,
            scrubber: Scrubber::new(client),
            seq: AtomicU64::new(0),
            full,
        })
    }

    /// Enregistre une réponse. Ne fait jamais échouer la requête : une erreur
    /// d'écriture est ignorée.
    pub(crate) fn record(
        &self,
        action: Option<&str>,
        params: &[(&str, &str)],
        response: &Result<(u16, bytes::Bytes)>,
        elapsed: Duration,
    ) {
        let name = action.unwrap_or("auth");
        if !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') {
            return;
        }
        let limit = (!self.full && LIST_ACTIONS.contains(&name)).then_some(LIST_LIMIT);
        let mut record = self.scrubber.record(action, params, response, limit);
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default();
        if let Value::Object(map) = &mut record {
            map.insert("at".into(), now.as_secs().into());
            map.insert("elapsed_ms".into(), (elapsed.as_millis() as u64).into());
        }
        let Ok(json) = serde_json::to_vec(&record) else {
            return;
        };
        // Horodatage puis numéro : l'ordre des noms est celui des requêtes.
        let seq = self.seq.fetch_add(1, Ordering::Relaxed);
        let file = format!("{:013}-{seq:06}-{name}.json", now.as_millis());
        // Écrit à côté puis renommé : un fichier `.json` est toujours complet
        // (l'envoi peut le lire à tout moment).
        let partial = self.dir.join(format!("{file}.part"));
        if fs::write(&partial, json).is_ok() && fs::rename(&partial, self.dir.join(file)).is_ok() {
            let factor = if self.full { 10 } else { 1 };
            prune(&self.dir, name, keep(name) * factor);
        } else {
            let _ = fs::remove_file(partial);
        }
    }
}

/// Efface les plus anciens fichiers de `action` au-delà de `keep`.
fn prune(dir: &Path, action: &str, keep: usize) {
    let suffix = format!("-{action}.json");
    let mut files: Vec<PathBuf> = recorded_files(dir)
        .into_iter()
        .filter(|p| file_name(p).ends_with(&suffix))
        .collect();
    if files.len() > keep {
        files.truncate(files.len() - keep);
        for old in files {
            let _ = fs::remove_file(old);
        }
    }
}

/// Fichiers enregistrés, du plus ancien au plus récent.
pub(crate) fn recorded_files(dir: &Path) -> Vec<PathBuf> {
    let mut files: Vec<PathBuf> = fs::read_dir(dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| entry.path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .collect();
    files.sort_by(|a, b| file_name(a).cmp(file_name(b)));
    files
}

fn file_name(path: &Path) -> &str {
    path.file_name()
        .and_then(|n| n.to_str())
        .unwrap_or_default()
}

fn storage(error: std::io::Error) -> Error {
    Error::Storage(error.kind().to_string())
}

/// Réponses enregistrées dans `dir` (vide s'il n'existe pas).
pub fn recording_summary(dir: impl AsRef<Path>) -> RecordingSummary {
    let files = recorded_files(dir.as_ref());
    RecordingSummary {
        responses: files.len() as u32,
        bytes: files
            .iter()
            .filter_map(|p| fs::metadata(p).ok())
            .map(|m| m.len())
            .sum(),
    }
}

/// Réunit les réponses de `dir` en un seul fichier JSON `out`, dans l'ordre
/// des requêtes. Un fichier illisible est ignoré.
pub fn export_recording(
    dir: impl AsRef<Path>,
    out: impl AsRef<Path>,
    exported_at: i64,
) -> Result<RecordingSummary> {
    let responses: Vec<Value> = recorded_files(dir.as_ref())
        .iter()
        .filter_map(|p| fs::read(p).ok())
        .filter_map(|bytes| serde_json::from_slice(&bytes).ok())
        .collect();
    let count = responses.len() as u32;
    let file = json!({
        "format": EXPORT_FORMAT,
        "app": crate::USER_AGENT,
        "exported_at": exported_at,
        "list_limit": LIST_LIMIT,
        "responses": responses,
    });
    let json = serde_json::to_vec_pretty(&file).map_err(|_| Error::InvalidResponse)?;
    fs::write(out.as_ref(), &json).map_err(storage)?;
    Ok(RecordingSummary {
        responses: count,
        bytes: json.len() as u64,
    })
}

/// Efface toutes les réponses enregistrées dans `dir`.
pub fn clear_recording(dir: impl AsRef<Path>) -> Result<()> {
    for file in recorded_files(dir.as_ref()) {
        fs::remove_file(file).map_err(storage)?;
    }
    Ok(())
}

/// Entrées d'une liste, tableau ou objet indexé (`{"0": {...}}`), avec leur
/// clé. `None` si la réponse n'est pas une liste (`null`, `""`, objet
/// `{"user_info": …}` d'identifiants refusés…).
fn entries(body: &str) -> Option<Vec<(Option<String>, &RawValue)>> {
    match body.trim_start().as_bytes().first() {
        Some(b'[') => serde_json::from_str::<Vec<&RawValue>>(body)
            .ok()
            .map(|list| list.into_iter().map(|raw| (None, raw)).collect()),
        Some(b'{') => {
            let map: BTreeMap<String, &RawValue> = serde_json::from_str(body).ok()?;
            if map.contains_key("user_info") {
                return None;
            }
            Some(map.into_iter().map(|(k, raw)| (Some(k), raw)).collect())
        }
        _ => None,
    }
}

/// `count` indices au plus, répartis de `0` à `len - 1`.
fn spread(len: usize, count: usize) -> impl Iterator<Item = usize> {
    let count = count.min(len);
    (0..count).map(move |i| i * len / count)
}

/// Nettoie les réponses : rien de ce qui identifie le compte ou le panneau
/// n'est écrit.
struct Scrubber {
    /// Textes remplacés, du plus long au plus court.
    secrets: Vec<(String, &'static str)>,
}

impl Scrubber {
    fn new(client: &XtreamClient) -> Self {
        let base = client.base_url();
        let host = base.host_str().unwrap_or_default().to_owned();
        let mut secrets = vec![
            (client.password().to_owned(), "password"),
            (client.username().to_owned(), "username"),
        ];
        if let Some(port) = base.port() {
            secrets.push((format!("{host}:{port}"), SCRUBBED_HOST));
        }
        secrets.push((host, SCRUBBED_HOST));
        secrets.retain(|(s, _)| !s.is_empty());
        secrets.sort_by_key(|(s, _)| std::cmp::Reverse(s.len()));
        Self { secrets }
    }

    /// Une requête et sa réponse nettoyée ; une liste du catalogue
    /// (`limit`) est réduite à un échantillon.
    fn record(
        &self,
        action: Option<&str>,
        params: &[(&str, &str)],
        response: &Result<(u16, bytes::Bytes)>,
        limit: Option<usize>,
    ) -> Value {
        let mut record = Map::new();
        record.insert("action".into(), action.map_or(Value::Null, Value::from));
        let params: Map<String, Value> = params
            .iter()
            .map(|(k, v)| ((*k).to_owned(), Value::from(self.scrub_text(v))))
            .collect();
        record.insert("params".into(), Value::Object(params));
        let (status, body) = match response {
            Ok(response) => response,
            Err(error) => {
                // Jamais le détail : il pourrait contenir l'URL.
                let kind = match error {
                    Error::Network(e) if e.is_timeout() => "timeout",
                    Error::Network(_) => "network",
                    _ => "other",
                };
                record.insert("error".into(), kind.into());
                return Value::Object(record);
            }
        };
        record.insert("status".into(), (*status).into());
        record.insert("bytes".into(), body.len().into());
        if !(200..300).contains(status) {
            record.insert("error".into(), "status".into());
        }
        let body = text(body);
        if body.trim().is_empty() {
            record.insert("body".into(), Value::Null);
            return Value::Object(record);
        }
        if let Some((limit, entries)) = limit.zip(entries(&body)) {
            record.insert("total".into(), entries.len().into());
            let picked: Vec<_> = spread(entries.len(), limit).map(|i| &entries[i]).collect();
            record.insert("kept".into(), picked.len().into());
            let keyed = picked.first().is_some_and(|(k, _)| k.is_some());
            let values = picked.into_iter().map(|(k, raw)| {
                let value = serde_json::from_str(raw.get()).unwrap_or(Value::Null);
                (k.clone().unwrap_or_default(), self.scrub(value))
            });
            let body = if keyed {
                Value::Object(values.collect())
            } else {
                Value::Array(values.map(|(_, v)| v).collect())
            };
            record.insert("body".into(), body);
        } else if let Ok(value) = serde_json::from_str::<Value>(&body) {
            record.insert("body".into(), self.scrub(value));
        } else {
            let start: String = body.chars().take(TEXT_LIMIT).collect();
            record.insert("text".into(), self.scrub_text(&start).into());
        }
        Value::Object(record)
    }

    fn scrub(&self, value: Value) -> Value {
        match value {
            Value::String(s) => Value::String(self.scrub_text(&s)),
            Value::Array(items) => Value::Array(items.into_iter().map(|v| self.scrub(v)).collect()),
            Value::Object(map) => Value::Object(
                map.into_iter()
                    .map(|(k, v)| {
                        let v = match (k.as_str(), v) {
                            ("username" | "password", Value::String(_)) => Value::String(k.clone()),
                            ("url", Value::String(_)) => Value::String(SCRUBBED_HOST.into()),
                            (_, v) => self.scrub(v),
                        };
                        (k, v)
                    })
                    .collect(),
            ),
            other => other,
        }
    }

    fn scrub_text(&self, s: &str) -> String {
        let mut out = s.to_owned();
        // Un texte court (« tv », « 12 ») remplacé partout abîmerait les
        // noms : il n'est remplacé que dans un champ qui ne contient que lui,
        // ou dans une URL (plus bas).
        for (secret, placeholder) in &self.secrets {
            if out == *secret || (secret.len() >= 4 && out.contains(secret.as_str())) {
                out = out.replace(secret.as_str(), placeholder);
            }
        }
        if is_ip(&out) {
            return SCRUBBED_IP.into();
        }
        if let Ok(mut url) = url::Url::parse(&out)
            && url.host_str().is_some()
            && url.set_host(Some(SCRUBBED_HOST)).is_ok()
        {
            let _ = url.set_port(None);
            let _ = url.set_username("");
            let _ = url.set_password(None);
            // `/live/{u}/{p}/1.ts`, `?username=…&password=…`.
            let segments: Option<Vec<String>> = url
                .path_segments()
                .map(|s| s.map(|seg| self.placeholder(seg)).collect());
            if let (Some(segments), Ok(mut path)) = (segments, url.path_segments_mut()) {
                path.clear().extend(segments);
            }
            if url.query().is_some() {
                let pairs: Vec<(String, String)> = url
                    .query_pairs()
                    .map(|(k, v)| {
                        let v = if SECRET_PARAMS.contains(&k.to_ascii_lowercase().as_str()) {
                            "secret".to_owned()
                        } else {
                            self.placeholder(&v)
                        };
                        (k.into_owned(), v)
                    })
                    .collect();
                url.query_pairs_mut().clear().extend_pairs(pairs);
            }
            return url.into();
        }
        out
    }

    /// Le texte de remplacement d'un champ qui n'est qu'un secret, sinon le
    /// champ tel quel.
    fn placeholder(&self, field: &str) -> String {
        self.secrets
            .iter()
            .find(|(secret, _)| secret == field)
            .map_or_else(|| field.to_owned(), |(_, p)| (*p).to_owned())
    }
}

fn is_ip(s: &str) -> bool {
    s.parse::<std::net::IpAddr>().is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spread_covers_the_whole_list() {
        assert_eq!(spread(10, 4).collect::<Vec<_>>(), [0, 2, 5, 7]);
        assert_eq!(spread(3, 300).collect::<Vec<_>>(), [0, 1, 2]);
        assert_eq!(spread(0, 5).count(), 0);
    }

    #[test]
    fn entries_tell_lists_from_objects() {
        assert_eq!(entries(r#"[{"a": 1}, 2]"#).unwrap().len(), 2);
        assert_eq!(entries(r#"{"0": {"a": 1}, "1": {}}"#).unwrap().len(), 2);
        assert!(entries(r#"{"user_info": {"auth": 0}}"#).is_none());
        assert!(entries("null").is_none());
    }

    #[test]
    fn caps_per_action() {
        assert_eq!(keep("get_vod_streams"), 3);
        assert_eq!(keep("get_short_epg"), 40);
        assert_eq!(keep("auth"), 5);
        assert_eq!(keep("get_something_new"), 10);
    }
}

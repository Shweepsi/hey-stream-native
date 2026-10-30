//! Capture des réponses d'un vrai panneau, pour en faire des réponses
//! simulées dans les tests (`core/tests/fixtures/`).
//!
//! Lancée à la main depuis l'APK debug, sur le réseau du propriétaire :
//! authentification, les six listes du catalogue, puis le guide des
//! programmes de quelques chaînes, avec une pause entre chaque requête. Les
//! réponses gardent leur forme exacte (nombres en texte, `null`, champs
//! manquants…) mais sont nettoyées avant d'être écrites :
//!
//! - identifiant, mot de passe et adresse du panneau remplacés partout ;
//! - hôte de chaque URL remplacé par `example.invalid` ;
//! - adresses IP remplacées par `192.0.2.1` (plage de documentation) ;
//! - longues listes réduites à un échantillon réparti sur toute la liste,
//!   avec leur nombre total d'entrées.

use std::collections::BTreeMap;
use std::time::Duration;

use serde::Deserialize;
use serde_json::value::RawValue;
use serde_json::{Map, Value, json};

use crate::client::XtreamClient;
use crate::error::{Error, Result};
use crate::lenient::{opt_i64, opt_string, text};

/// Hôte écrit à la place de celui de toute URL.
pub const CAPTURE_HOST: &str = "example.invalid";
/// Adresse écrite à la place de toute adresse IP.
const CAPTURE_IP: &str = "192.0.2.1";
/// Version du format du fichier de capture.
const CAPTURE_FORMAT: u32 = 1;
/// Longueur gardée d'une réponse qui n'est pas du JSON (page d'erreur…).
const TEXT_LIMIT: usize = 2000;

/// Listes du catalogue, dans l'ordre des requêtes.
const LIST_ACTIONS: [&str; 6] = [
    "get_live_categories",
    "get_live_streams",
    "get_vod_categories",
    "get_vod_streams",
    "get_series_categories",
    "get_series",
];

#[derive(Debug, Clone)]
pub struct CaptureOptions {
    /// Entrées gardées par liste, réparties sur toute la liste.
    pub list_limit: usize,
    /// Chaînes dont le guide des programmes est demandé.
    pub epg_channels: usize,
    /// Pause entre deux requêtes : un usage normal de l'app, pas une rafale.
    pub pause: Duration,
}

impl Default for CaptureOptions {
    fn default() -> Self {
        Self {
            list_limit: 300,
            epg_channels: 8,
            pause: Duration::from_millis(1500),
        }
    }
}

/// Fichier de capture (JSON) et nombre de requêtes faites et échouées.
#[derive(Debug)]
pub struct Capture {
    pub json: Vec<u8>,
    pub requests: u32,
    pub failed: u32,
}

/// Capture les réponses du panneau. S'arrête dès l'authentification si les
/// identifiants sont refusés ; ensuite, une requête qui échoue est notée
/// dans le fichier et la capture continue.
pub async fn capture(
    client: &XtreamClient,
    options: &CaptureOptions,
    captured_at: i64,
) -> Result<Capture> {
    let scrubber = Scrubber::new(client);
    let mut responses = Vec::new();
    let mut failed = 0;

    let auth = client.fetch_raw(None, &[]).await?;
    let accepted = auth.0 < 300
        && serde_json::from_str::<Value>(&text(&auth.1))
            .ok()
            .and_then(|v| v.pointer("/user_info/auth").cloned())
            .is_some_and(|a| a == json!(1) || a == json!("1") || a == json!(true));
    if !accepted {
        return Err(Error::Unauthorized);
    }
    responses.push(scrubber.record(None, &[], Ok(auth), None));

    let mut channels = Vec::new();
    for action in LIST_ACTIONS {
        tokio::time::sleep(options.pause).await;
        let response = client.fetch_raw(Some(action), &[]).await;
        if let (Ok((200..=299, body)), "get_live_streams") = (&response, action) {
            channels = epg_channels(body, options.epg_channels);
        }
        let record = scrubber.record(Some(action), &[], response, Some(options.list_limit));
        failed += u32::from(record.get("error").is_some());
        responses.push(record);
    }

    for id in channels {
        tokio::time::sleep(options.pause).await;
        let id = id.to_string();
        let params = [("stream_id", id.as_str()), ("limit", crate::epg::EPG_LIMIT)];
        let response = client.fetch_raw(Some("get_short_epg"), &params).await;
        let record = scrubber.record(Some("get_short_epg"), &params, response, None);
        failed += u32::from(record.get("error").is_some());
        responses.push(record);
    }

    let requests = responses.len() as u32;
    let file = json!({
        "format": CAPTURE_FORMAT,
        "app": crate::USER_AGENT,
        "captured_at": captured_at,
        "list_limit": options.list_limit,
        "responses": responses,
    });
    let json = serde_json::to_vec_pretty(&file).map_err(|_| Error::InvalidResponse)?;
    Ok(Capture {
        json,
        requests,
        failed,
    })
}

#[derive(Deserialize)]
struct Channel {
    #[serde(default, deserialize_with = "opt_i64")]
    stream_id: Option<i64>,
    #[serde(default, deserialize_with = "opt_string")]
    epg_channel_id: Option<String>,
}

/// Chaînes qui annoncent un guide, réparties sur toute la liste.
fn epg_channels(body: &[u8], count: usize) -> Vec<i64> {
    let body = text(body);
    let Some(entries) = entries(&body) else {
        return Vec::new();
    };
    let ids: Vec<i64> = entries
        .iter()
        .filter_map(|(_, raw)| serde_json::from_str::<Channel>(raw.get()).ok())
        .filter(|c| c.epg_channel_id.is_some())
        .filter_map(|c| c.stream_id)
        .collect();
    spread(ids.len(), count).map(|i| ids[i]).collect()
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
/// n'est écrit dans le fichier.
struct Scrubber {
    /// Textes remplacés partout, du plus long au plus court.
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
            secrets.push((format!("{host}:{port}"), CAPTURE_HOST));
        }
        secrets.push((host, CAPTURE_HOST));
        // Un texte trop court (« tv », « 1 ») remplacé partout abîmerait les
        // noms : il n'est remplacé que dans un champ qui ne contient que lui.
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
        response: Result<(u16, bytes::Bytes)>,
        limit: Option<usize>,
    ) -> Value {
        let mut record = Map::new();
        record.insert("action".into(), action.map_or(Value::Null, Value::from));
        let params: Map<String, Value> = params
            .iter()
            .map(|(k, v)| ((*k).to_owned(), Value::from(*v)))
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
        record.insert("status".into(), status.into());
        record.insert("bytes".into(), body.len().into());
        if !(200..300).contains(&status) {
            record.insert("error".into(), "status".into());
        }
        let body = text(&body);
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
                            ("url", Value::String(_)) => Value::String(CAPTURE_HOST.into()),
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
        for (secret, placeholder) in &self.secrets {
            if out == *secret || (secret.len() >= 4 && out.contains(secret.as_str())) {
                out = out.replace(secret.as_str(), placeholder);
            }
        }
        if is_ip(&out) {
            return CAPTURE_IP.into();
        }
        if let Ok(mut url) = url::Url::parse(&out)
            && url.host_str().is_some()
            && url.set_host(Some(CAPTURE_HOST)).is_ok()
        {
            let _ = url.set_port(None);
            let _ = url.set_username("");
            let _ = url.set_password(None);
            // Identifiants courts, que `secrets` ne remplace pas dans un
            // texte : `/live/{u}/{p}/1.ts`, `?username=…&password=…`.
            let segments: Option<Vec<String>> = url
                .path_segments()
                .map(|s| s.map(|seg| self.placeholder(seg)).collect());
            if let (Some(segments), Ok(mut path)) = (segments, url.path_segments_mut()) {
                path.clear().extend(segments);
            }
            if url.query().is_some() {
                let pairs: Vec<(String, String)> = url
                    .query_pairs()
                    .map(|(k, v)| (k.into_owned(), self.placeholder(&v)))
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
}

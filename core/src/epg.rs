//! EPG court : programme en cours et suivant d'une chaîne.
//!
//! Un seul appel `get_short_epg` par chaîne, mis en cache : on ne redemande
//! que si le cache ne couvre plus le programme suivant, et jamais plus d'une
//! fois toutes les 15 minutes par chaîne (le fournisseur peut bannir un
//! compte trop bavard).

use base64::Engine;
use rusqlite::{OptionalExtension, params};
use serde::Deserialize;
use serde_json::Value;

use crate::catalog::Catalog;
use crate::client::XtreamClient;
use crate::error::Result;
use crate::lenient::{opt_i64, opt_string};

/// Délai minimal entre deux demandes d'EPG pour une même chaîne.
pub const EPG_MIN_INTERVAL_SECS: i64 = 15 * 60;
/// Nombre de programmes demandés : le cours, le suivant et une marge.
const EPG_LIMIT: &str = "4";

/// Un programme TV.
#[derive(Debug, Clone, PartialEq)]
pub struct Programme {
    pub title: String,
    pub description: Option<String>,
    /// Début et fin, en secondes Unix.
    pub start: i64,
    pub stop: i64,
}

/// Programme en cours et suivant.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct NowNext {
    pub now: Option<Programme>,
    pub next: Option<Programme>,
}

#[derive(Deserialize)]
struct RawListing {
    #[serde(default, deserialize_with = "opt_string")]
    title: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    description: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    start_timestamp: Option<i64>,
    #[serde(default, deserialize_with = "opt_i64")]
    stop_timestamp: Option<i64>,
}

impl XtreamClient {
    /// Prochains programmes d'une chaîne (`get_short_epg`). Les titres et
    /// descriptions arrivent encodés en base64 ; une entrée illisible est
    /// ignorée.
    pub async fn short_epg(&self, stream_id: i64) -> Result<Vec<Programme>> {
        let id = stream_id.to_string();
        let value = self
            .get(
                Some("get_short_epg"),
                &[("stream_id", id.as_str()), ("limit", EPG_LIMIT)],
            )
            .await?;
        let listings = match value {
            Value::Object(mut map) => map.remove("epg_listings").unwrap_or(Value::Null),
            other => other,
        };
        let listings: Vec<RawListing> = crate::lenient::list(listings)
            .into_iter()
            .filter(|raw: &RawListing| {
                matches!((raw.start_timestamp, raw.stop_timestamp), (Some(a), Some(b)) if b > a)
                    && raw.title.is_some()
            })
            .collect();
        // Le format se décide pour toute la réponse : base64 seulement si
        // chaque texte se décode en texte lisible. Un panneau qui écrit en
        // clair a forcément des titres qui échouent (espaces, accents), et
        // « Info » ou « JT13 » ne sont alors pas décodés par erreur.
        let encoded = listings
            .iter()
            .flat_map(|raw| [raw.title.as_deref(), raw.description.as_deref()])
            .flatten()
            .all(|text| decode_base64(text).is_some());
        let text = |value: Option<&str>| -> Option<String> {
            let value = value?.trim();
            let text = if encoded {
                decode_base64(value)?
            } else {
                value.to_owned()
            };
            Some(text.trim().to_owned()).filter(|t| !t.is_empty())
        };
        let mut programmes: Vec<Programme> = listings
            .iter()
            .filter_map(|raw| {
                Some(Programme {
                    title: text(raw.title.as_deref())?,
                    description: text(raw.description.as_deref()),
                    start: raw.start_timestamp?,
                    stop: raw.stop_timestamp?,
                })
            })
            .collect();
        programmes.sort_by_key(|p| p.start);
        Ok(programmes)
    }
}

/// Décode un texte base64 en texte lisible ; `None` si ce n'est pas du
/// base64 ou si le résultat n'est pas du texte (UTF-8 invalide, caractères
/// de contrôle).
fn decode_base64(text: &str) -> Option<String> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(text.trim())
        .ok()?;
    let decoded = String::from_utf8(bytes).ok()?;
    decoded
        .chars()
        .all(|c| !c.is_control() || matches!(c, '\n' | '\r' | '\t'))
        .then_some(decoded)
}

impl Catalog {
    /// Programme en cours et suivant d'après le cache.
    pub fn now_next(&self, stream_id: i64, now: i64) -> Result<NowNext> {
        let read = |sql: &str| -> Result<Option<Programme>> {
            Ok(self
                .conn
                .query_row(sql, params![stream_id, now], |r| {
                    Ok(Programme {
                        title: r.get(0)?,
                        description: r.get(1)?,
                        start: r.get(2)?,
                        stop: r.get(3)?,
                    })
                })
                .optional()?)
        };
        Ok(NowNext {
            now: read(
                "SELECT title, description, start, stop FROM epg
                 WHERE stream_id = ?1 AND start <= ?2 AND stop > ?2 ORDER BY start DESC LIMIT 1",
            )?,
            next: read(
                "SELECT title, description, start, stop FROM epg
                 WHERE stream_id = ?1 AND start > ?2 ORDER BY start LIMIT 1",
            )?,
        })
    }

    /// Vrai s'il faut redemander l'EPG de la chaîne : le cache ne connaît pas
    /// le programme suivant, et la dernière demande date d'au moins 15 min.
    pub fn epg_needs_fetch(&self, stream_id: i64, now: i64) -> Result<bool> {
        let fetched_at: Option<i64> = self
            .conn
            .query_row(
                "SELECT fetched_at FROM epg_fetch WHERE stream_id = ?1",
                [stream_id],
                |r| r.get(0),
            )
            .optional()?;
        if fetched_at.is_some_and(|at| now >= at && now - at < EPG_MIN_INTERVAL_SECS) {
            return Ok(false);
        }
        Ok(self.now_next(stream_id, now)?.next.is_none())
    }

    /// Réserve la demande d'EPG d'une chaîne : vrai s'il faut interroger le
    /// fournisseur, et la tentative est alors notée tout de suite. Un second
    /// appel, même simultané, trouve la réservation et n'envoie rien : jamais
    /// deux requêtes pour la même chaîne en 15 min.
    pub fn claim_epg_fetch(&mut self, stream_id: i64, now: i64) -> Result<bool> {
        if !self.epg_needs_fetch(stream_id, now)? {
            return Ok(false);
        }
        self.mark_epg_attempt(stream_id, now)?;
        Ok(true)
    }

    fn mark_epg_attempt(&mut self, stream_id: i64, now: i64) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO epg_fetch (stream_id, fetched_at) VALUES (?1, ?2)",
            params![stream_id, now],
        )?;
        Ok(())
    }

    /// Enregistre les programmes reçus pour une chaîne et efface ceux qui
    /// sont terminés. Une liste vide garde le cache de la chaîne.
    pub fn store_epg(&mut self, stream_id: i64, programmes: &[Programme], now: i64) -> Result<()> {
        let tx = self.conn.transaction()?;
        tx.execute("DELETE FROM epg WHERE stop <= ?1", [now])?;
        // Une réponse vide, ou faite seulement de programmes terminés
        // (fréquent par intermittence), ne remplace pas des programmes
        // encore valables.
        let upcoming: Vec<&Programme> = programmes.iter().filter(|p| p.stop > now).collect();
        if !upcoming.is_empty() {
            tx.execute("DELETE FROM epg WHERE stream_id = ?1", [stream_id])?;
            let mut insert = tx.prepare(
                "INSERT OR REPLACE INTO epg (stream_id, start, stop, title, description)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
            )?;
            for p in upcoming {
                insert.execute(params![stream_id, p.start, p.stop, p.title, p.description])?;
            }
        }
        tx.execute(
            "INSERT OR REPLACE INTO epg_fetch (stream_id, fetched_at) VALUES (?1, ?2)",
            params![stream_id, now],
        )?;
        tx.commit()?;
        Ok(())
    }
}

/// Programme en cours et suivant d'une chaîne : depuis le cache, avec une
/// demande au fournisseur seulement si nécessaire. Une erreur réseau n'est
/// pas fatale : on renvoie ce que le cache connaît.
pub async fn now_next_for(
    client: &XtreamClient,
    catalog: &mut Catalog,
    stream_id: i64,
    now: i64,
) -> Result<NowNext> {
    if catalog.claim_epg_fetch(stream_id, now)?
        && let Ok(programmes) = client.short_epg(stream_id).await
    {
        // En cas d'erreur, le cache reste tel quel et la tentative est déjà
        // notée : pas de nouvel essai avant 15 min.
        catalog.store_epg(stream_id, &programmes, now)?;
    }
    catalog.now_next(stream_id, now)
}

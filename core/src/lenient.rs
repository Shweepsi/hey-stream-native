//! Désérialisation tolérante aux écarts des panneaux Xtream.

use std::borrow::Cow;
use std::collections::BTreeMap;

use serde::Deserialize;
use serde::de::{DeserializeOwned, Deserializer};
use serde_json::Value;
use serde_json::value::RawValue;

use crate::error::Error;

/// Texte ou nombre → `Option<String>` ; vide, `null` ou autre → `None`.
pub fn opt_string<'de, D: Deserializer<'de>>(d: D) -> Result<Option<String>, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::String(s) if !s.trim().is_empty() => Some(s.trim().to_owned()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    })
}

/// Nombre ou texte numérique → `Option<i64>`.
pub fn opt_i64<'de, D: Deserializer<'de>>(d: D) -> Result<Option<i64>, D::Error> {
    Ok(value_to_i64(&Value::deserialize(d)?))
}

/// Booléen exprimé en `1`, `"1"`, `true` ou `"true"`.
pub fn flag<'de, D: Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Bool(b) => b,
        v => value_to_i64(&v) == Some(1) || v.as_str() == Some("true"),
    })
}

fn value_to_i64(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse::<f64>().ok().map(|f| f as i64),
        _ => None,
    }
}

/// Texte d'une réponse. Les panneaux envoient parfois des noms en Latin-1 au
/// milieu d'UTF-8 : chaque octet invalide est lu comme un caractère Latin-1
/// (« Caf\xe9 » → « Café ») au lieu de rendre toute la réponse illisible.
/// Sans copie quand la réponse est déjà en UTF-8 valide.
pub(crate) fn text(body: &[u8]) -> Cow<'_, str> {
    if let Ok(text) = std::str::from_utf8(body) {
        return Cow::Borrowed(text);
    }
    let mut text = String::with_capacity(body.len() + 16);
    for chunk in body.utf8_chunks() {
        text.push_str(chunk.valid());
        text.extend(chunk.invalid().iter().map(|&b| char::from(b)));
    }
    Cow::Owned(text)
}

/// Liste tolérante, lue directement depuis la réponse brute : `null`, `""`,
/// `false` ou un objet vide donnent une liste vide ; un objet indexé
/// (`{"0": {...}}`) est lu comme une liste ; chaque entrée illisible est
/// ignorée. Un objet `{"user_info": …}` signale des identifiants refusés.
///
/// Chaque entrée est désérialisée depuis sa tranche de texte (`RawValue`),
/// sans construire d'arbre `Value` pour toute la liste.
pub fn parse_list<T: DeserializeOwned>(body: &[u8]) -> Result<Vec<T>, Error> {
    let body = text(body);
    let entries: Vec<&RawValue> = match body.trim_start().as_bytes().first() {
        Some(b'[') => serde_json::from_str(&body).map_err(|_| Error::InvalidResponse)?,
        Some(b'{') => {
            let map: BTreeMap<String, &RawValue> =
                serde_json::from_str(&body).map_err(|_| Error::InvalidResponse)?;
            if map.contains_key("user_info") {
                return Err(Error::Unauthorized);
            }
            map.into_values().collect()
        }
        // null, "", false, nombre ou réponse vide : liste vide.
        Some(_) => {
            serde_json::from_str::<&RawValue>(&body).map_err(|_| Error::InvalidResponse)?;
            Vec::new()
        }
        None => Vec::new(),
    };
    Ok(entries
        .into_iter()
        .filter_map(|raw| serde_json::from_str(raw.get()).ok())
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Debug, Deserialize, PartialEq)]
    struct Item {
        id: i64,
    }

    #[test]
    fn parse_list_shapes() {
        let ids = |body: &str| -> Vec<i64> {
            parse_list::<Item>(body.as_bytes())
                .unwrap()
                .into_iter()
                .map(|i| i.id)
                .collect()
        };
        assert_eq!(ids(r#"[{"id": 1}, {"id": "x"}, {"id": 2}]"#), [1, 2]);
        assert_eq!(ids(r#"{"0": {"id": 3}, "1": {"id": 4}}"#), [3, 4]);
        for empty in ["", "  ", "null", "\"\"", "false", "{}", "[]"] {
            assert!(ids(empty).is_empty(), "{empty:?}");
        }
        assert!(matches!(
            parse_list::<Item>(b"[{"),
            Err(Error::InvalidResponse)
        ));
        assert!(matches!(
            parse_list::<Item>(b"<html>"),
            Err(Error::InvalidResponse)
        ));
        assert!(matches!(
            parse_list::<Item>(br#"{"user_info": {"auth": 0}}"#),
            Err(Error::Unauthorized)
        ));
    }

    #[derive(Debug, Deserialize)]
    struct Named {
        name: String,
    }

    #[test]
    fn latin1_bytes_do_not_spoil_the_list() {
        let names: Vec<String> =
            parse_list::<Named>(b"[{\"name\": \"Caf\xe9\"}, {\"name\": \"Th\xc3\xa9\"}]")
                .unwrap()
                .into_iter()
                .map(|n| n.name)
                .collect();
        assert_eq!(names, ["Café", "Thé"]);
    }
}

//! Désérialisation tolérante aux écarts des panneaux Xtream.

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

/// Liste tolérante, lue directement depuis la réponse brute : `null`, `""`,
/// `false` ou un objet vide donnent une liste vide ; un objet indexé
/// (`{"0": {...}}`) est lu comme une liste ; chaque entrée illisible est
/// ignorée. Un objet `{"user_info": …}` signale des identifiants refusés.
///
/// Chaque entrée est désérialisée depuis sa tranche de texte (`RawValue`),
/// sans construire d'arbre `Value` pour toute la liste.
pub fn parse_list<T: DeserializeOwned>(body: &[u8]) -> Result<Vec<T>, Error> {
    let entries: Vec<&RawValue> = match body.iter().find(|b| !b.is_ascii_whitespace()) {
        Some(b'[') => serde_json::from_slice(body).map_err(|_| Error::InvalidResponse)?,
        Some(b'{') => {
            let map: BTreeMap<String, &RawValue> =
                serde_json::from_slice(body).map_err(|_| Error::InvalidResponse)?;
            if map.contains_key("user_info") {
                return Err(Error::Unauthorized);
            }
            map.into_values().collect()
        }
        // null, "", false, nombre ou réponse vide : liste vide.
        Some(_) => {
            serde_json::from_slice::<&RawValue>(body).map_err(|_| Error::InvalidResponse)?;
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
}

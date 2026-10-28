//! Désérialisation tolérante aux écarts des panneaux Xtream.

use serde::Deserialize;
use serde::de::{DeserializeOwned, Deserializer};
use serde_json::Value;

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

/// Liste tolérante : `null`, `""`, `false` ou un objet vide donnent une liste
/// vide ; un objet indexé (`{"0": {...}}`) est lu comme une liste ; chaque
/// entrée illisible est ignorée.
pub fn list<T: DeserializeOwned>(value: Value) -> Vec<T> {
    let items = match value {
        Value::Array(items) => items,
        Value::Object(map) => map.into_iter().map(|(_, v)| v).collect(),
        _ => Vec::new(),
    };
    items
        .into_iter()
        .filter_map(|item| serde_json::from_value(item).ok())
        .collect()
}

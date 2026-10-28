use serde::Deserialize;

use crate::lenient::{flag, opt_i64, opt_string};

/// Identifiants d'une source Xtream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Credentials {
    pub server_url: String,
    pub username: String,
    pub password: String,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Category {
    #[serde(deserialize_with = "required_id")]
    pub category_id: String,
    #[serde(default, deserialize_with = "opt_string")]
    pub category_name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub parent_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct LiveStream {
    #[serde(deserialize_with = "required_i64")]
    pub stream_id: i64,
    #[serde(default, deserialize_with = "opt_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub stream_icon: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub epg_channel_id: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub category_id: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    pub num: Option<i64>,
    #[serde(default, deserialize_with = "flag")]
    pub tv_archive: bool,
    #[serde(default, deserialize_with = "opt_i64")]
    pub tv_archive_duration: Option<i64>,
    /// Marquage adulte du fournisseur (`"is_adult": "1"`), verrouillé par le
    /// contrôle parental quelle que soit la catégorie.
    #[serde(default, deserialize_with = "flag")]
    pub is_adult: bool,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Movie {
    #[serde(deserialize_with = "required_i64")]
    pub stream_id: i64,
    #[serde(default, deserialize_with = "opt_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub stream_icon: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub category_id: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub container_extension: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub rating: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    pub added: Option<i64>,
    /// Marquage adulte du fournisseur (`"is_adult": "1"`), verrouillé par le
    /// contrôle parental quelle que soit la catégorie.
    #[serde(default, deserialize_with = "flag")]
    pub is_adult: bool,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Series {
    #[serde(deserialize_with = "required_i64")]
    pub series_id: i64,
    #[serde(default, deserialize_with = "opt_string")]
    pub name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub cover: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub category_id: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub plot: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub rating: Option<String>,
    /// Marquage adulte du fournisseur (`"is_adult": "1"`), verrouillé par le
    /// contrôle parental quelle que soit la catégorie.
    #[serde(default, deserialize_with = "flag")]
    pub is_adult: bool,
}

/// Compte et serveur renvoyés par l'authentification.
#[derive(Debug, Clone, PartialEq)]
pub struct AccountInfo {
    /// Date d'expiration (timestamp Unix), absente pour un compte illimité.
    pub expires_at: Option<i64>,
    pub max_connections: Option<i64>,
    pub active_connections: Option<i64>,
    pub server: ServerInfo,
}

#[derive(Debug, Clone, PartialEq, Default, Deserialize)]
pub struct ServerInfo {
    #[serde(default, deserialize_with = "opt_string")]
    pub url: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub port: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub https_port: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub server_protocol: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    pub timezone: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RawUserInfo {
    #[serde(default, deserialize_with = "flag")]
    pub auth: bool,
    #[serde(default, deserialize_with = "opt_string")]
    pub status: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    pub exp_date: Option<i64>,
    #[serde(default, deserialize_with = "opt_i64")]
    pub max_connections: Option<i64>,
    #[serde(default, deserialize_with = "opt_i64")]
    pub active_cons: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct RawAuth {
    pub user_info: RawUserInfo,
    #[serde(default)]
    pub server_info: Option<ServerInfo>,
}

fn required_id<'de, D: serde::Deserializer<'de>>(d: D) -> Result<String, D::Error> {
    opt_string(d)?.ok_or_else(|| serde::de::Error::custom("identifiant manquant"))
}

fn required_i64<'de, D: serde::Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
    opt_i64(d)?.ok_or_else(|| serde::de::Error::custom("identifiant manquant"))
}

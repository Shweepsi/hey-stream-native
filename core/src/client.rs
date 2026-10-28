use std::time::Duration;

use serde_json::Value;

use crate::error::{Error, Result};
use crate::lenient::list;
use crate::model::{AccountInfo, Category, Credentials, LiveStream, Movie, RawAuth, Series};
use crate::url::{StreamKind, normalize_server_url};

/// Client d'une source Xtream Codes.
#[derive(Debug, Clone)]
pub struct XtreamClient {
    base: url::Url,
    username: String,
    password: String,
    http: reqwest::Client,
}

impl XtreamClient {
    pub fn new(credentials: &Credentials) -> Result<Self> {
        let username = credentials.username.trim();
        let password = credentials.password.trim();
        if username.is_empty() || password.is_empty() {
            return Err(Error::MissingCredentials);
        }
        let http = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(Error::Network)?;
        Ok(Self {
            base: normalize_server_url(&credentials.server_url)?,
            username: username.to_owned(),
            password: password.to_owned(),
            http,
        })
    }

    /// Vérifie les identifiants et renvoie l'état du compte.
    pub async fn authenticate(&self) -> Result<AccountInfo> {
        let value = self.get(None, &[]).await?;
        let raw: RawAuth = serde_json::from_value(value).map_err(|_| Error::Unauthorized)?;
        if !raw.user_info.auth {
            return Err(Error::Unauthorized);
        }
        let status = raw.user_info.status.as_deref().unwrap_or("Active");
        if !status.eq_ignore_ascii_case("active") {
            return Err(Error::AccountInactive);
        }
        Ok(AccountInfo {
            expires_at: raw.user_info.exp_date,
            max_connections: raw.user_info.max_connections,
            active_connections: raw.user_info.active_cons,
            server: raw.server_info.unwrap_or_default(),
        })
    }

    pub async fn live_categories(&self) -> Result<Vec<Category>> {
        Ok(list(self.get(Some("get_live_categories"), &[]).await?))
    }

    pub async fn live_streams(&self, category_id: Option<&str>) -> Result<Vec<LiveStream>> {
        Ok(list(
            self.get(Some("get_live_streams"), &category(category_id))
                .await?,
        ))
    }

    pub async fn movie_categories(&self) -> Result<Vec<Category>> {
        Ok(list(self.get(Some("get_vod_categories"), &[]).await?))
    }

    pub async fn movies(&self, category_id: Option<&str>) -> Result<Vec<Movie>> {
        Ok(list(
            self.get(Some("get_vod_streams"), &category(category_id))
                .await?,
        ))
    }

    pub async fn series_categories(&self) -> Result<Vec<Category>> {
        Ok(list(self.get(Some("get_series_categories"), &[]).await?))
    }

    pub async fn series(&self, category_id: Option<&str>) -> Result<Vec<Series>> {
        Ok(list(
            self.get(Some("get_series"), &category(category_id)).await?,
        ))
    }

    /// URL de lecture d'un flux : `{serveur}/{live|movie|series}/{u}/{p}/{id}.{ext}`.
    /// L'extension par défaut est `ts` pour le direct, `mp4` sinon.
    pub fn stream_url(&self, kind: StreamKind, id: i64, extension: Option<&str>) -> url::Url {
        let ext = extension.unwrap_or(match kind {
            StreamKind::Live => "ts",
            _ => "mp4",
        });
        let mut url = self.base.clone();
        url.path_segments_mut()
            .expect("URL http(s) validée")
            .pop_if_empty()
            .extend([
                kind.path(),
                &self.username,
                &self.password,
                &format!("{id}.{ext}"),
            ]);
        url
    }

    async fn get(&self, action: Option<&str>, extra: &[(&str, &str)]) -> Result<Value> {
        let mut url = self.base.clone();
        url.path_segments_mut()
            .expect("URL http(s) validée")
            .pop_if_empty()
            .push("player_api.php");
        {
            let mut query = url.query_pairs_mut();
            query.append_pair("username", &self.username);
            query.append_pair("password", &self.password);
            if let Some(action) = action {
                query.append_pair("action", action);
            }
            for (key, value) in extra {
                query.append_pair(key, value);
            }
        }
        let response = self
            .http
            .get(url)
            .header(reqwest::header::ACCEPT, "application/json")
            .send()
            .await
            .map_err(Error::Network)?;
        let status = response.status().as_u16();
        match status {
            200..=299 => {}
            401 | 403 => return Err(Error::Unauthorized),
            404 => return Err(Error::NotFound),
            500..=599 => return Err(Error::Server(status)),
            _ => return Err(Error::UnexpectedStatus(status)),
        }
        let body = response.bytes().await.map_err(Error::Network)?;
        if body.iter().all(u8::is_ascii_whitespace) {
            return Ok(Value::Null);
        }
        let value: Value = serde_json::from_slice(&body).map_err(|_| Error::InvalidResponse)?;
        // Une liste demandée avec des identifiants refusés revient souvent en
        // HTTP 200 avec `{"user_info": {"auth": 0}}` au lieu d'une liste.
        if action.is_some() && value.pointer("/user_info/auth").is_some() {
            return Err(Error::Unauthorized);
        }
        Ok(value)
    }
}

fn category(category_id: Option<&str>) -> Vec<(&'static str, &str)> {
    category_id
        .map(|id| vec![("category_id", id)])
        .unwrap_or_default()
}

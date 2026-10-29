use std::time::Duration;

use serde::de::DeserializeOwned;
use serde_json::Value;

use crate::error::{Error, Result};
use crate::lenient::{parse_list, text};
use crate::model::{AccountInfo, Category, Credentials, LiveStream, Movie, RawAuth, Series};
use crate::url::{StreamKind, normalize_server_url};

/// Identifiant honnête envoyé aux serveurs : certains panneaux refusent les
/// requêtes sans User-Agent. On n'imite jamais une autre application.
pub const USER_AGENT: &str = concat!("HeyStream/", env!("CARGO_PKG_VERSION"));

/// Client d'une source Xtream Codes.
#[derive(Clone)]
pub struct XtreamClient {
    base: url::Url,
    username: String,
    password: String,
    http: reqwest::Client,
}

/// Sans identifiants : un `{:?}` ne doit jamais les journaliser.
impl std::fmt::Debug for XtreamClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("XtreamClient").finish_non_exhaustive()
    }
}

impl XtreamClient {
    pub fn new(credentials: &Credentials) -> Result<Self> {
        let username = credentials.username.trim();
        let password = credentials.password.trim();
        if username.is_empty() || password.is_empty() {
            return Err(Error::MissingCredentials);
        }
        let builder = reqwest::Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(60));
        #[cfg(target_os = "android")]
        let builder = builder.tls_certs_only(android_roots());
        let http = builder.build().map_err(Error::network)?;
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
            allowed_output_formats: raw.user_info.allowed_output_formats,
            server: raw.server_info.unwrap_or_default(),
        })
    }

    pub async fn live_categories(&self) -> Result<Vec<Category>> {
        self.get_list("get_live_categories", &[]).await
    }

    pub async fn live_streams(&self, category_id: Option<&str>) -> Result<Vec<LiveStream>> {
        self.get_list("get_live_streams", &category(category_id))
            .await
    }

    pub async fn movie_categories(&self) -> Result<Vec<Category>> {
        self.get_list("get_vod_categories", &[]).await
    }

    pub async fn movies(&self, category_id: Option<&str>) -> Result<Vec<Movie>> {
        self.get_list("get_vod_streams", &category(category_id))
            .await
    }

    pub async fn series_categories(&self) -> Result<Vec<Category>> {
        self.get_list("get_series_categories", &[]).await
    }

    pub async fn series(&self, category_id: Option<&str>) -> Result<Vec<Series>> {
        self.get_list("get_series", &category(category_id)).await
    }

    pub(crate) fn base_url(&self) -> &url::Url {
        &self.base
    }

    pub(crate) fn username(&self) -> &str {
        &self.username
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

    /// Requête JSON lue entièrement en `Value` : réservée aux petites
    /// réponses (authentification, fiches, EPG).
    pub(crate) async fn get(&self, action: Option<&str>, extra: &[(&str, &str)]) -> Result<Value> {
        let body = self.fetch(action, extra).await?;
        if body.iter().all(u8::is_ascii_whitespace) {
            return Ok(Value::Null);
        }
        let value: Value =
            serde_json::from_str(&text(&body)).map_err(|_| Error::InvalidResponse)?;
        // Une liste demandée avec des identifiants refusés revient souvent en
        // HTTP 200 avec `{"user_info": {"auth": 0}}` au lieu d'une liste.
        if action.is_some() && value.pointer("/user_info/auth").is_some() {
            return Err(Error::Unauthorized);
        }
        Ok(value)
    }

    /// Liste du catalogue, lue entrée par entrée sans arbre `Value`
    /// intermédiaire : 30 000 films ne sont jamais en mémoire deux fois sous
    /// forme d'objets JSON (appareil de référence à 2 Go).
    async fn get_list<T: DeserializeOwned>(
        &self,
        action: &str,
        extra: &[(&str, &str)],
    ) -> Result<Vec<T>> {
        let body = self.fetch(Some(action), extra).await?;
        parse_list(&body)
    }

    async fn fetch(&self, action: Option<&str>, extra: &[(&str, &str)]) -> Result<bytes::Bytes> {
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
            .map_err(Error::network)?;
        let status = response.status().as_u16();
        match status {
            200..=299 => {}
            401 | 403 => return Err(Error::Unauthorized),
            404 => return Err(Error::NotFound),
            500..=599 => return Err(Error::Server(status)),
            _ => return Err(Error::UnexpectedStatus(status)),
        }
        response.bytes().await.map_err(Error::network)
    }
}

fn category(category_id: Option<&str>) -> Vec<(&'static str, &str)> {
    category_id
        .map(|id| vec![("category_id", id)])
        .unwrap_or_default()
}

/// Racines de confiance sur Android : celles de Mozilla, embarquées. Le
/// vérificateur du système (`rustls-platform-verifier`) demanderait d'être
/// initialisé par l'app avec le contexte Android (JNI), sans quoi chaque
/// requête HTTPS échouerait.
#[cfg(any(target_os = "android", test))]
fn android_roots() -> Vec<reqwest::Certificate> {
    webpki_root_certs::TLS_SERVER_ROOT_CERTS
        .iter()
        .filter_map(|der| reqwest::Certificate::from_der(der).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn android_roots_are_all_readable() {
        let roots = android_roots();
        assert!(roots.len() > 100);
        assert_eq!(roots.len(), webpki_root_certs::TLS_SERVER_ROOT_CERTS.len());
        reqwest::Client::builder()
            .tls_certs_only(roots)
            .build()
            .expect("client construit avec les seules racines embarquées");
    }
}

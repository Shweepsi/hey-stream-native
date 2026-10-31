//! Mise à jour de l'app depuis les releases GitHub du dépôt.
//!
//! Le workflow `release` publie chaque version (étiquette `vX.Y.Z`, marquée
//! « pre-release » tant qu'on est avant la 1.0) avec son APK. On lit la
//! liste des releases (pas `/releases/latest`, qui ignore les
//! pre-releases), on garde la plus haute version qui a un APK, et on la
//! propose si elle dépasse la version installée. Le téléchargement est
//! vérifié (taille, et empreinte SHA-256 quand GitHub la fournit) avant
//! d'être remis à l'app, qui l'installe.

use std::io::Write;
use std::path::Path;
use std::time::Duration;

use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::client::{check_status, http_builder};
use crate::error::{Error, Result};
use crate::lenient::{opt_i64, opt_string};

/// API GitHub et dépôt des releases de l'app.
pub const GITHUB_API: &str = "https://api.github.com";
pub const UPDATE_REPO: &str = "Shweepsi/hey-stream-native";

/// Une version publiée, plus récente que celle installée.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppUpdate {
    /// « 0.6.0 », sans le `v` de l'étiquette.
    pub version: String,
    /// Notes de la release (entrée du CHANGELOG), en Markdown.
    pub notes: String,
    pub apk_url: String,
    /// Taille de l'APK en octets (0 si inconnue).
    pub size: u64,
    /// Empreinte SHA-256 de l'APK en hexadécimal, si GitHub la fournit.
    pub sha256: Option<String>,
    /// Clé à présenter pour le téléchargement (APK testeur, servi par le
    /// service de collecte) ; aucune pour GitHub.
    pub token: Option<String>,
}

#[derive(Deserialize)]
struct RawRelease {
    #[serde(default, deserialize_with = "opt_string")]
    tag_name: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default, deserialize_with = "opt_string")]
    body: Option<String>,
    #[serde(default)]
    assets: Vec<RawAsset>,
}

#[derive(Deserialize)]
struct RawAsset {
    #[serde(default, deserialize_with = "opt_string")]
    name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    browser_download_url: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    size: Option<i64>,
    /// `sha256:<hex>`.
    #[serde(default, deserialize_with = "opt_string")]
    digest: Option<String>,
}

/// Version `X.Y.Z` (un `v` en tête et un suffixe `-…` sont acceptés).
fn parse_version(text: &str) -> Option<(u64, u64, u64)> {
    let text = text.trim().trim_start_matches(['v', 'V']);
    let core = text.split(['-', '+']).next()?;
    let mut parts = core.split('.').map(|p| p.parse::<u64>().ok());
    let version = (parts.next()??, parts.next()??, parts.next()??);
    parts.next().is_none().then_some(version)
}

/// Suffixe de l'APK testeur (`hey-stream-X.Y.Z-t.apk`), qui envoie ses
/// données d'usage (voir [`crate::telemetry`]). Il n'est jamais publié sur
/// GitHub : seulement remis par le propriétaire, puis mis à jour depuis le
/// service de collecte ([`check_tester_update`]). Par prudence, un APK de ce
/// nom sur GitHub n'est jamais proposé.
pub const TESTER_APK_SUFFIX: &str = "-t.apk";

/// Plus haute version publiée avec un APK public, si elle dépasse `current`.
pub async fn check_update(api_base: &str, repo: &str, current: &str) -> Result<Option<AppUpdate>> {
    let current = parse_version(current).ok_or(Error::InvalidResponse)?;
    let http = http_builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(Error::network)?;
    let url = format!(
        "{}/repos/{repo}/releases?per_page=20",
        api_base.trim_end_matches('/')
    );
    let response = http
        .get(url)
        .header(reqwest::header::ACCEPT, "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send()
        .await
        .map_err(Error::network)?;
    check_status(response.status().as_u16())?;
    let body = response.bytes().await.map_err(Error::network)?;
    let releases: Vec<RawRelease> = crate::lenient::parse_list(&body)?;
    let newest = releases
        .into_iter()
        .filter(|r| !r.draft)
        .filter_map(|r| {
            let version = parse_version(r.tag_name.as_deref()?)?;
            let apk = r.assets.into_iter().find(|a| {
                a.name.as_deref().is_some_and(|n| {
                    let n = n.to_ascii_lowercase();
                    n.ends_with(".apk") && !n.ends_with(TESTER_APK_SUFFIX)
                }) && a.browser_download_url.is_some()
            })?;
            Some((version, r.body, apk))
        })
        .max_by_key(|(version, _, _)| *version);
    Ok(newest.filter(|(version, _, _)| *version > current).map(
        |((major, minor, patch), body, apk)| AppUpdate {
            version: format!("{major}.{minor}.{patch}"),
            notes: body.unwrap_or_default(),
            apk_url: apk.browser_download_url.unwrap_or_default(),
            size: apk.size.and_then(|s| u64::try_from(s).ok()).unwrap_or(0),
            sha256: apk
                .digest
                .and_then(|d| d.strip_prefix("sha256:").map(str::to_ascii_lowercase))
                .filter(|hex| hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit())),
            token: None,
        },
    ))
}

#[derive(Deserialize)]
struct TesterRelease {
    #[serde(default, deserialize_with = "opt_string")]
    version: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    notes: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    size: Option<i64>,
    #[serde(default, deserialize_with = "opt_string")]
    sha256: Option<String>,
}

/// Dernier APK testeur, servi par le service de collecte (`endpoint`, clé
/// d'envoi `key`), s'il dépasse `current`. Pas de version publiée : `None`.
pub async fn check_tester_update(
    endpoint: &str,
    key: &str,
    current: &str,
    device: &str,
) -> Result<Option<AppUpdate>> {
    let current = parse_version(current).ok_or(Error::InvalidResponse)?;
    let http = http_builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(Error::network)?;
    let endpoint = endpoint.trim_end_matches('/');
    let response = http
        .get(format!("{endpoint}/v1/update"))
        .bearer_auth(key)
        .header("X-Device", device)
        .header(
            "X-App-Version",
            format!("{}.{}.{}", current.0, current.1, current.2),
        )
        .send()
        .await
        .map_err(Error::network)?;
    let status = response.status().as_u16();
    if status == 404 {
        return Ok(None);
    }
    check_status(status)?;
    let body = response.bytes().await.map_err(Error::network)?;
    let release: TesterRelease =
        serde_json::from_slice(&body).map_err(|_| Error::InvalidResponse)?;
    let Some((major, minor, patch)) = release.version.as_deref().and_then(parse_version) else {
        return Err(Error::InvalidResponse);
    };
    if (major, minor, patch) <= current {
        return Ok(None);
    }
    let version = format!("{major}.{minor}.{patch}");
    Ok(Some(AppUpdate {
        apk_url: format!("{endpoint}/v1/update/apk?version={version}"),
        version,
        notes: release.notes.unwrap_or_default(),
        size: release
            .size
            .and_then(|s| u64::try_from(s).ok())
            .unwrap_or(0),
        sha256: release
            .sha256
            .map(|hex| hex.to_ascii_lowercase())
            .filter(|hex| hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit())),
        token: Some(key.to_owned()),
    }))
}

/// Télécharge l'APK de `update` dans `path`. `progress(reçus, total)` est
/// appelé au fil du téléchargement (`total` à 0 s'il est inconnu). Un fichier
/// de taille ou d'empreinte inattendue est effacé (`InvalidResponse`) : rien
/// n'est laissé dans `path`.
pub async fn download_update(
    update: &AppUpdate,
    path: &Path,
    mut progress: impl FnMut(u64, u64),
) -> Result<()> {
    let http = http_builder()
        .read_timeout(Duration::from_secs(30))
        .build()
        .map_err(Error::network)?;
    let mut request = http.get(&update.apk_url);
    if let Some(token) = &update.token {
        request = request.bearer_auth(token);
    }
    let mut response = request.send().await.map_err(Error::network)?;
    check_status(response.status().as_u16())?;
    let total = match update.size {
        0 => response.content_length().unwrap_or(0),
        size => size,
    };
    let partial = path.with_extension("part");
    let result = async {
        let mut file = std::fs::File::create(&partial).map_err(storage)?;
        let mut hasher = Sha256::new();
        let mut received = 0u64;
        progress(0, total);
        while let Some(chunk) = response.chunk().await.map_err(Error::network)? {
            file.write_all(&chunk).map_err(storage)?;
            hasher.update(&chunk);
            received += chunk.len() as u64;
            progress(received, total);
        }
        file.sync_all().map_err(storage)?;
        let hex: String = hasher
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let size_ok = update.size == 0 || received == update.size;
        let digest_ok = update
            .sha256
            .as_ref()
            .is_none_or(|expected| *expected == hex);
        if !size_ok || !digest_ok || received == 0 {
            return Err(Error::InvalidResponse);
        }
        std::fs::rename(&partial, path).map_err(storage)
    }
    .await;
    if result.is_err() {
        let _ = std::fs::remove_file(&partial);
    }
    result
}

fn storage(error: std::io::Error) -> Error {
    Error::Storage(error.kind().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions() {
        assert_eq!(parse_version("v0.6.0"), Some((0, 6, 0)));
        assert_eq!(parse_version("1.10.2"), Some((1, 10, 2)));
        assert_eq!(parse_version("v1.0.0-rc1"), Some((1, 0, 0)));
        assert!(parse_version("v1.0").is_none());
        assert!(parse_version("1.0.0.0").is_none());
        assert!(parse_version("latest").is_none());
        assert!(parse_version("0.10.0") > parse_version("0.9.9"));
    }
}

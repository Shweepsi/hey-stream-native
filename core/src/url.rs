use crate::error::{Error, Result};

/// Normalise l'adresse saisie : espaces et `/` finaux retirés ; un hôte sans
/// schéma reçoit `http://`, le cas le plus courant des panneaux Xtream (une
/// app native n'a pas de contrainte de contenu mixte).
pub fn normalize_server_url(input: &str) -> Result<url::Url> {
    let trimmed = input.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(Error::InvalidServerUrl);
    }
    let with_scheme = if trimmed.contains("://") {
        trimmed.to_owned()
    } else {
        format!("http://{trimmed}")
    };
    let url = url::Url::parse(&with_scheme).map_err(|_| Error::InvalidServerUrl)?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err(Error::InvalidServerUrl);
    }
    Ok(url)
}

/// Type de flux, qui détermine le chemin de lecture Xtream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamKind {
    Live,
    Movie,
    Series,
}

impl StreamKind {
    pub(crate) fn path(self) -> &'static str {
        match self {
            StreamKind::Live => "live",
            StreamKind::Movie => "movie",
            StreamKind::Series => "series",
        }
    }
}

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

/// Format d'un flux en direct.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LiveFormat {
    /// MPEG-TS continu : le plus direct, lu par Media3 (Android).
    Ts,
    /// HLS (`m3u8`) : le seul que lit AVPlayer (Apple).
    Hls,
}

impl LiveFormat {
    pub fn extension(self) -> &'static str {
        match self {
            LiveFormat::Ts => "ts",
            LiveFormat::Hls => "m3u8",
        }
    }

    /// Format à demander au fournisseur : celui que préfère le lecteur, sauf
    /// si le fournisseur ne l'autorise pas et autorise l'autre. Formats
    /// inconnus (jamais connecté, ou non précisés) : celui que préfère le
    /// lecteur.
    pub fn choose(preferred: LiveFormat, allowed: &[String]) -> LiveFormat {
        let allows = |f: LiveFormat| allowed.iter().any(|a| a == f.extension());
        let other = match preferred {
            LiveFormat::Ts => LiveFormat::Hls,
            LiveFormat::Hls => LiveFormat::Ts,
        };
        if !allows(preferred) && allows(other) {
            other
        } else {
            preferred
        }
    }
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

#[cfg(test)]
mod tests {
    use super::LiveFormat::{self, Hls, Ts};

    #[test]
    fn live_format_follows_the_provider() {
        let allowed =
            |list: &[&str]| -> Vec<String> { list.iter().map(|s| s.to_string()).collect() };
        // Inconnus : la préférence du lecteur.
        assert_eq!(LiveFormat::choose(Ts, &[]), Ts);
        assert_eq!(LiveFormat::choose(Hls, &[]), Hls);
        assert_eq!(
            LiveFormat::choose(Ts, &allowed(&["m3u8", "ts", "rtmp"])),
            Ts
        );
        assert_eq!(LiveFormat::choose(Hls, &allowed(&["m3u8", "ts"])), Hls);
        // Seul l'autre est autorisé.
        assert_eq!(LiveFormat::choose(Ts, &allowed(&["m3u8"])), Hls);
        assert_eq!(LiveFormat::choose(Hls, &allowed(&["ts"])), Ts);
        // Aucun des deux : on tente quand même la préférence.
        assert_eq!(LiveFormat::choose(Hls, &allowed(&["rtmp"])), Hls);
        assert_eq!(Hls.extension(), "m3u8");
    }
}

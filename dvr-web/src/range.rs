//! En-tête `Range: bytes=…` : le lecteur vidéo ne peut se déplacer dans un
//! fichier de plusieurs Go que si le serveur sait n'en envoyer qu'un morceau.
//!
//! N'utilise que la bibliothèque standard.

/// Morceau demandé d'un fichier.
#[derive(Debug, PartialEq, Eq)]
pub enum ByteRange {
    /// Pas de plage (ou plage qu'on ne gère pas) : tout le fichier.
    Whole,
    /// Octets `start` à `end` inclus.
    Partial { start: u64, end: u64 },
    /// Hors du fichier : réponse 416.
    Unsatisfiable,
}

/// Lit l'en-tête pour un fichier de `len` octets. Une plage multiple ou illisible est ignorée.
pub fn parse(header: Option<&str>, len: u64) -> ByteRange {
    let Some(spec) = header.and_then(|h| h.trim().strip_prefix("bytes=")) else {
        return ByteRange::Whole;
    };
    if spec.contains(',') {
        return ByteRange::Whole;
    }
    let Some((first, last)) = spec.trim().split_once('-') else {
        return ByteRange::Whole;
    };
    let (first, last) = (first.trim(), last.trim());
    let range = if first.is_empty() {
        // « -n » : les n derniers octets.
        match last.parse::<u64>() {
            Ok(n) if n > 0 && len > 0 => (len.saturating_sub(n), len - 1),
            Ok(_) => return ByteRange::Unsatisfiable,
            Err(_) => return ByteRange::Whole,
        }
    } else {
        let Ok(start) = first.parse::<u64>() else {
            return ByteRange::Whole;
        };
        let end = if last.is_empty() {
            u64::MAX
        } else if let Ok(end) = last.parse::<u64>() {
            end
        } else {
            return ByteRange::Whole;
        };
        if start >= len || start > end {
            return ByteRange::Unsatisfiable;
        }
        (start, end.min(len - 1))
    };
    ByteRange::Partial {
        start: range.0,
        end: range.1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn partial(start: u64, end: u64) -> ByteRange {
        ByteRange::Partial { start, end }
    }

    #[test]
    fn closed_open_and_suffix_ranges() {
        assert_eq!(parse(Some("bytes=0-99"), 1000), partial(0, 99));
        assert_eq!(parse(Some("bytes=900-"), 1000), partial(900, 999));
        assert_eq!(parse(Some("bytes=-100"), 1000), partial(900, 999));
        assert_eq!(parse(Some("bytes=-5000"), 1000), partial(0, 999));
        assert_eq!(parse(Some("bytes=990-5000"), 1000), partial(990, 999));
        assert_eq!(parse(Some(" bytes=5000000000-5000000099 "), 9_000_000_000), partial(5_000_000_000, 5_000_000_099));
    }

    #[test]
    fn out_of_file_is_unsatisfiable() {
        assert_eq!(parse(Some("bytes=1000-"), 1000), ByteRange::Unsatisfiable);
        assert_eq!(parse(Some("bytes=50-10"), 1000), ByteRange::Unsatisfiable);
        assert_eq!(parse(Some("bytes=-0"), 1000), ByteRange::Unsatisfiable);
        assert_eq!(parse(Some("bytes=0-"), 0), ByteRange::Unsatisfiable);
    }

    #[test]
    fn missing_or_unusable_header_means_the_whole_file() {
        assert_eq!(parse(None, 1000), ByteRange::Whole);
        assert_eq!(parse(Some("items=0-5"), 1000), ByteRange::Whole);
        assert_eq!(parse(Some("bytes=0-5,10-20"), 1000), ByteRange::Whole);
        assert_eq!(parse(Some("bytes=abc-def"), 1000), ByteRange::Whole);
        assert_eq!(parse(Some("bytes=12"), 1000), ByteRange::Whole);
    }
}

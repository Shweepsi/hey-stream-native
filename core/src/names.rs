//! Noms affichés des films, séries et chaînes.
//!
//! Les fournisseurs écrivent la langue dans le nom (« FR - Dune », « |FR|
//! CANAL+ », « The Crown (2016)_fr »). Le français est la langue de l'app :
//! sa mention est retirée, une seule fois, à l'enregistrement du catalogue.
//! Les autres langues restent écrites (« EN - Dune ») pour que deux versions
//! d'un titre ne se confondent pas, comme la qualité (« FR -4K Gravity »
//! devient « 4K Gravity »). Les noms de catégories ne passent pas par ici.

/// Mentions du français reconnues, sans tenir compte de la casse.
const FRENCH: [&str; 2] = ["fr", "vf"];

/// Nom sans la mention du français en tête (« FR - », « FR: », « |FR| »,
/// « VF - ») ni en fin (« … FR », « …_fr », « …-fr »). Rien n'est retiré si
/// le nom ne contient que la mention.
pub fn clean_name(raw: &str) -> String {
    let trimmed = raw.trim();
    let cleaned = strip_suffix(strip_prefix(trimmed)).trim();
    if cleaned.is_empty() {
        trimmed.to_owned()
    } else {
        cleaned.to_owned()
    }
}

fn is_french(tag: &str) -> bool {
    FRENCH.iter().any(|f| tag.eq_ignore_ascii_case(f))
}

fn strip_prefix(name: &str) -> &str {
    // « |FR| Canal+ »
    if let Some(rest) = name.strip_prefix('|') {
        if let Some((tag, after)) = rest.split_once('|')
            && is_french(tag.trim())
        {
            return after.trim_start();
        }
        return name;
    }
    // « FR - Dune », « FR -4K Gravity », « VF: Dune »
    let Some(tag) = name.get(..2).filter(|t| is_french(t)) else {
        return name;
    };
    let rest = name[tag.len()..].trim_start();
    match rest.chars().next() {
        Some(c @ ('-' | ':' | '|')) => rest[c.len_utf8()..].trim_start(),
        _ => name,
    }
}

fn strip_suffix(name: &str) -> &str {
    // « The Crown (2016) FR », « Bad Blood (2017)_fr »
    let Some(start) = name
        .len()
        .checked_sub(2)
        .filter(|&i| name.is_char_boundary(i))
    else {
        return name;
    };
    if !is_french(&name[start..]) {
        return name;
    }
    let head = &name[..start];
    let without_separators = head.trim_end_matches([' ', '_', '-']);
    // Il faut un séparateur, et un nom devant : « Affr » ou « FR » seul restent.
    if without_separators.len() == head.len() || without_separators.trim().is_empty() {
        return name;
    }
    without_separators
}

#[cfg(test)]
mod tests {
    use super::clean_name;

    #[test]
    fn french_prefixes_go() {
        assert_eq!(
            clean_name("FR - To Philly with Love (2026) FHD"),
            "To Philly with Love (2026) FHD"
        );
        assert_eq!(clean_name("|FR| canal+ FOOT UHD"), "canal+ FOOT UHD");
        assert_eq!(clean_name("|FR|  CANAL+ SPORT"), "CANAL+ SPORT");
        assert_eq!(clean_name("Fr - Dune"), "Dune");
        assert_eq!(clean_name("|VF| Canal+"), "Canal+");
        assert_eq!(clean_name("VF: Dune"), "Dune");
    }

    #[test]
    fn quality_stays_so_versions_differ() {
        assert_eq!(clean_name("FR -4K Gravity (2013)"), "4K Gravity (2013)");
        assert_eq!(clean_name("FR -4KL Apollo 13"), "4KL Apollo 13");
    }

    #[test]
    fn french_suffixes_go() {
        assert_eq!(clean_name("The Crown (2016) FR"), "The Crown (2016)");
        assert_eq!(clean_name("Bad Blood (2017)_fr"), "Bad Blood (2017)");
        assert_eq!(
            clean_name("When They See Us (2019)-fr"),
            "When They See Us (2019)"
        );
    }

    #[test]
    fn other_languages_and_titles_stay() {
        assert_eq!(clean_name("EN - Dune (2021)"), "EN - Dune (2021)");
        assert_eq!(clean_name("|BE| BEIN SPORTS 1"), "|BE| BEIN SPORTS 1");
        assert_eq!(clean_name("VOSTFR: Dune"), "VOSTFR: Dune");
        assert_eq!(clean_name("France 2"), "France 2");
        assert_eq!(clean_name("Frasier"), "Frasier");
        assert_eq!(clean_name("OSS 117 - Le Caire"), "OSS 117 - Le Caire");
        assert_eq!(
            clean_name("FR - Affection (2026) MULTI"),
            "Affection (2026) MULTI"
        );
        assert_eq!(clean_name("Affr"), "Affr");
    }

    #[test]
    fn a_bare_mention_is_kept() {
        assert_eq!(clean_name("FR -"), "FR -");
        assert_eq!(clean_name("|FR|"), "|FR|");
        assert_eq!(clean_name("  FR  "), "FR");
        assert_eq!(clean_name("Été"), "Été");
    }
}

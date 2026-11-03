//! Noms affichés des films, séries et chaînes.
//!
//! Les fournisseurs écrivent la langue dans le nom (« FR - Dune », « |FR|
//! CANAL+ », « The Crown (2016)_fr »). Le français est la langue de l'app :
//! sa mention est retirée, une seule fois, à l'enregistrement du catalogue.
//! Les autres langues restent écrites (« EN - Dune »). La mention de qualité
//! en tête (« 4KL Black Box », « |UHD| Dune ») est retirée du nom affiché
//! (décision du 2026-11-02) mais reste cherchable ([`search_name`]). Les
//! noms de catégories ne passent pas par ici.
//!
//! Les films et les séries (pas les chaînes) passent en plus par
//! [`clean_title`] (décision du 2026-11-03) : espaces en trop, noms de
//! fichiers de publication (« 15.Aout.2001.1080p… »), mentions techniques en
//! fin de nom (« FHD », « HDR », « x265 »…). VOSTFR, VOST et MULTI restent.
//! [`title_without_year`] sert la fiche, qui montre déjà l'année.

/// Mentions du français reconnues, sans tenir compte de la casse.
const FRENCH: [&str; 2] = ["fr", "vf"];

/// Mentions de qualité retirées en tête de nom, sans tenir compte de la casse.
const QUALITY: [&str; 5] = ["4k", "4kl", "uhd", "fhd", "hd"];

/// Nom affiché : sans la mention du français en tête (« FR - », « FR: »,
/// « |FR| », « VF - ») ni en fin (« … FR », « …_fr », « …-fr »), ni les
/// mentions de qualité en tête (« 4KL Black Box », « uhd Alita », « 4K FR -
/// Gravity », « |4K| UHD Dune »). Rien n'est retiré si le nom ne contient
/// que des mentions.
pub fn clean_name(raw: &str) -> String {
    clean(raw, true)
}

/// Nom de recherche : comme [`clean_name`], mais la qualité reste, pour que
/// « 4k gravity » trouve encore la version 4K.
pub fn search_name(raw: &str) -> String {
    clean(raw, false)
}

/// Mentions de langue gardées à l'affichage d'un film ou d'une série : elles
/// disent quelle version on va lancer.
const KEPT_LANGUAGE: &[&str] = &["MULTI", "VOSTFR", "VOST"];

/// Mentions techniques retirées en fin de nom d'un film ou d'une série, en
/// majuscules et sans `[]()|` autour (voir [`mention_key`]). « Full HD »,
/// en deux mots, est traité à part.
const TECHNICAL: &[&str] = &[
    "HD", "FHD", "UHD", "4K", "HDR", "HDR10", "HDR10+", "HEVC", "H265", "H.265", "H264", "H.264",
    "X265", "X264", "10BIT", "720P", "1080P", "2160P",
];

/// Autres mots d'un nom de fichier de publication (sources, codecs, formats)
/// qui ne font plus partie du titre. Seul le début avant un « - » compte :
/// « WEB-DL », « x264-GROUPE ».
const RELEASE_TAGS: &[&str] = &[
    "480P", "576P", "WEB", "WEBDL", "WEBRIP", "BLU", "BLURAY", "BDRIP", "BRRIP", "HDRIP", "DVDRIP",
    "HDTV", "AAC", "AC3", "DTS", "REMUX", "MKV", "MP4", "AVI",
];

/// Nom d'un film ou d'une série à afficher : les règles de [`clean_name`],
/// puis :
/// - une seule espace entre les mots, aucune après « ( » ni avant « ) » ;
/// - un nom de fichier de publication devient un titre : « 15.Aout.2001.1080p.
///   TF1.WEB-DL » donne « 15 Aout (2001) » ;
/// - les mentions techniques en fin de nom disparaissent (« Dune (2021) FHD »
///   donne « Dune (2021) »), sauf MULTI, VOSTFR et VOST. Rien n'est retiré s'il
///   ne resterait que des mentions.
///
/// Pour les films et les séries seulement : les noms de chaînes gardent
/// leurs mentions ([`clean_name`]). Tout reste cherchable ([`search_name`]).
pub fn clean_title(raw: &str) -> String {
    let base = clean_name(raw);
    let spaced = tidy_spaces(&base);
    let titled = dotted_title(&spaced).unwrap_or(spaced);
    let stripped = strip_technical_tail(&titled);
    if stripped.is_empty() { base } else { stripped }
}

/// Titre sans « (année) » quand la fiche montre déjà cette année : « 22 Jump
/// Street (2014) » donne « 22 Jump Street » au-dessus de « 2014 ». `year` est
/// celle de la fiche (« 2014 » ou « 2014-06-13 »). Sans la même année entre
/// parenthèses dans le titre, il reste tel quel ; les grilles gardent l'année,
/// qui distingue « Dune (1984) » de « Dune (2021) ».
pub fn title_without_year(name: &str, year: &str) -> String {
    let digits: String = year.trim().chars().take(4).collect();
    if digits.len() != 4 || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return name.to_owned();
    }
    let mark = format!("({digits})");
    let Some(at) = name.find(&mark) else {
        return name.to_owned();
    };
    let before = name[..at].trim_end();
    let after = name[at + mark.len()..].trim_start();
    match (before.is_empty(), after.is_empty()) {
        (true, true) => name.to_owned(),
        (false, true) => before.to_owned(),
        (true, false) => after.to_owned(),
        (false, false) => format!("{before} {after}"),
    }
}

/// Mot en majuscules, sans `[]()|` autour, et ses lettres en exposant
/// (« ᵁᴴᴰ ») ramenées à l'écriture normale : « [hdr10+] » donne « HDR10+ » et
/// « ᵁᴴᴰ » donne « UHD ».
fn mention_key(word: &str) -> String {
    word.trim_matches(|c: char| matches!(c, '[' | ']' | '(' | ')' | '|'))
        .chars()
        .flat_map(|c| {
            let plain = match c {
                'ᴴ' => 'H',
                'ᴰ' => 'D',
                'ᶠ' => 'F',
                'ᵁ' => 'U',
                '⁴' => '4',
                'ᴷ' => 'K',
                other => other,
            };
            plain.to_uppercase()
        })
        .collect()
}

/// Une seule espace entre les mots, aucune après « ( » ni avant « ) » :
/// « 9 Bullets ( 2022) » donne « 9 Bullets (2022) ».
fn tidy_spaces(name: &str) -> String {
    name.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .replace("( ", "(")
        .replace(" )", ")")
}

/// Année d'un nom de fichier : de 1900 à 2030.
fn is_year(part: &str) -> bool {
    part.len() == 4
        && part
            .parse::<u16>()
            .is_ok_and(|year| (1900..=2030).contains(&year))
}

/// Vrai pour un mot de nom de fichier qui n'est plus le titre : résolution,
/// source, codec.
fn is_release_tag(part: &str) -> bool {
    let key = mention_key(part);
    let head = key.split('-').next().unwrap_or_default();
    TECHNICAL.contains(&key.as_str()) || TECHNICAL.contains(&head) || RELEASE_TAGS.contains(&head)
}

/// Nom de fichier de publication sans espace : « 15.Aout.2001.1080p.TF1.
/// WEB-DL » donne « 15 Aout (2001) ». Il faut au moins trois morceaux séparés
/// par « . » ou « _ », dont une année ou une mention technique : « S.W.A.T. »
/// et « Mr.Robot » restent tels quels. Le titre s'arrête à l'année, la
/// dernière avant la première mention technique ; sans année, à cette
/// mention. MULTI, VOSTFR et VOST sont gardés à la fin.
fn dotted_title(name: &str) -> Option<String> {
    if name.contains(char::is_whitespace) {
        return None;
    }
    let parts: Vec<&str> = name.split(['.', '_']).filter(|p| !p.is_empty()).collect();
    if parts.len() < 3 {
        return None;
    }
    let first_tag = parts.iter().position(|p| is_release_tag(p));
    // Jamais en tête : le titre ne peut pas être vide (« 1917.2019.2160p »).
    let year_at = (1..first_tag.unwrap_or(parts.len()))
        .rev()
        .find(|&i| is_year(parts[i]));
    let (end, tail) = match (year_at, first_tag) {
        (Some(year), _) => (year, year + 1),
        (None, Some(tag)) => (tag, tag),
        (None, None) => return None,
    };
    if end == 0 {
        return None;
    }
    let mut title = parts[..end].join(" ");
    if let Some(year) = year_at {
        title.push_str(" (");
        title.push_str(parts[year]);
        title.push(')');
    }
    for part in &parts[tail..] {
        if KEPT_LANGUAGE.contains(&mention_key(part).as_str()) {
            title.push(' ');
            title.push_str(part);
        }
    }
    Some(title)
}

/// Retire les mentions techniques en fin de nom (« Dune (2021) MULTI 4K
/// HDR » donne « Dune (2021) MULTI »). Les mentions de langue gardées restent,
/// à leur place relative. Rien n'est retiré s'il ne reste aucun titre.
fn strip_technical_tail(name: &str) -> String {
    let words: Vec<&str> = name.split_whitespace().collect();
    let mut start = words.len();
    let mut removed = false;
    while start > 0 {
        let key = mention_key(words[start - 1]);
        if KEPT_LANGUAGE.contains(&key.as_str()) {
            start -= 1;
        } else if key == "HD" && start >= 2 && mention_key(words[start - 2]) == "FULL" {
            start -= 2;
            removed = true;
        } else if TECHNICAL.contains(&key.as_str()) {
            start -= 1;
            removed = true;
        } else {
            break;
        }
    }
    if !removed {
        return name.to_owned();
    }
    let head = words[..start].join(" ");
    // « Dune (2021) - FHD » : le tiret qui séparait la mention part avec elle.
    let head = head
        .trim_end_matches(|c: char| c.is_whitespace() || matches!(c, '-' | '–' | '|' | '/' | ':'));
    if head.is_empty() {
        return name.to_owned();
    }
    let kept: Vec<&str> = words[start..]
        .iter()
        .copied()
        .filter(|word| KEPT_LANGUAGE.contains(&mention_key(word).as_str()))
        .collect();
    if kept.is_empty() {
        head.to_owned()
    } else {
        format!("{head} {}", kept.join(" "))
    }
}

fn clean(raw: &str, drop_quality: bool) -> String {
    let trimmed = raw.trim();
    let mut rest = trimmed;
    // Mentions empilées, dans n'importe quel ordre : « 4K FR - Gravity ».
    loop {
        let mut next = strip_prefix(rest).trim_start();
        if drop_quality {
            next = strip_quality(next).trim_start();
        }
        if next.len() == rest.len() {
            break;
        }
        rest = next;
    }
    let cleaned = strip_suffix(rest).trim();
    if cleaned.is_empty() {
        trimmed.to_owned()
    } else {
        cleaned.to_owned()
    }
}

/// « 4K Gravity », « UHD - Gravity », « fhd: Gravity », « |4K| Gravity » →
/// « Gravity ». La mention doit être un mot entier suivi d'un séparateur :
/// « 4Kids » et « HDTV » restent. Rien n'est retiré s'il ne reste rien.
fn strip_quality(name: &str) -> &str {
    let (tag, rest) = match name.strip_prefix('|') {
        Some(inner) => match inner.split_once('|') {
            Some(pair) => pair,
            None => return name,
        },
        None => match name.split_once(is_separator) {
            Some(pair) => pair,
            None => return name,
        },
    };
    if !QUALITY.iter().any(|q| tag.trim().eq_ignore_ascii_case(q)) {
        return name;
    }
    let rest = rest.trim_start_matches(is_separator);
    if rest.is_empty() { name } else { rest }
}

fn is_separator(c: char) -> bool {
    c.is_whitespace() || matches!(c, '-' | ':' | '|')
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
    use super::{clean_name, clean_title, search_name, title_without_year};

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
    fn leading_quality_goes() {
        assert_eq!(clean_name("FR -4K Gravity (2013)"), "Gravity (2013)");
        assert_eq!(clean_name("FR -4KL Apollo 13"), "Apollo 13");
        assert_eq!(clean_name("4KL Black Box (2021)"), "Black Box (2021)");
        assert_eq!(clean_name("uhd Alita, Battle Angel"), "Alita, Battle Angel");
        assert_eq!(clean_name("|FR| FHD - Dune"), "Dune");
        assert_eq!(clean_name("HD: Dune"), "Dune");
    }

    #[test]
    fn stacked_and_bracketed_mentions_go() {
        assert_eq!(clean_name("4K FR - Gravity"), "Gravity");
        assert_eq!(clean_name("UHD |FR| Dune"), "Dune");
        assert_eq!(clean_name("|4K| Dune"), "Dune");
        assert_eq!(clean_name("|FR| 4K UHD Dune"), "Dune");
        assert_eq!(clean_name("|BE| 4K Dune"), "|BE| 4K Dune");
    }

    #[test]
    fn the_search_name_keeps_the_quality() {
        assert_eq!(search_name("FR -4K Gravity (2013)"), "4K Gravity (2013)");
        assert_eq!(search_name("|FR| canal+ FOOT UHD"), "canal+ FOOT UHD");
    }

    #[test]
    fn quality_inside_a_word_or_alone_stays() {
        assert_eq!(clean_name("4Kids TV"), "4Kids TV");
        assert_eq!(clean_name("HDTV Show"), "HDTV Show");
        assert_eq!(clean_name("4K"), "4K");
        assert_eq!(clean_name("FR - 4K"), "4K");
        assert_eq!(clean_name("canal+ FOOT UHD"), "canal+ FOOT UHD");
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

    #[test]
    fn titles_keep_single_spaces() {
        assert_eq!(
            clean_title("3 Tickets to Paradise  (2021)"),
            "3 Tickets to Paradise (2021)"
        );
        assert_eq!(clean_title("9 Bullets ( 2022)"), "9 Bullets (2022)");
        assert_eq!(clean_title("Dune ( 2021 )"), "Dune (2021)");
        assert_eq!(clean_title("  Dune   (2021)  "), "Dune (2021)");
    }

    #[test]
    fn titles_lose_trailing_technical_mentions() {
        assert_eq!(clean_title("Dune (2021) FHD"), "Dune (2021)");
        assert_eq!(clean_title("Dune (2021) Full HD"), "Dune (2021)");
        assert_eq!(clean_title("Dune (2021) [HDR10+] x265"), "Dune (2021)");
        assert_eq!(clean_title("Dune (2021) |4K|"), "Dune (2021)");
        assert_eq!(
            clean_title("Dune (2021) 2160p HEVC x265 10bit"),
            "Dune (2021)"
        );
        assert_eq!(clean_title("Dune (2021) - FHD"), "Dune (2021)");
        assert_eq!(
            clean_title("FR - To Philly with Love (2026) FHD"),
            "To Philly with Love (2026)"
        );
    }

    #[test]
    fn superscript_mentions_are_technical_too() {
        assert_eq!(clean_title("Dune (2021) ᵁᴴᴰ"), "Dune (2021)");
        assert_eq!(clean_title("Dune (2021) ⁴ᴷ"), "Dune (2021)");
        assert_eq!(clean_title("Dune (2021) ᶠᴴᴰ"), "Dune (2021)");
    }

    #[test]
    fn titles_keep_multi_and_vostfr() {
        assert_eq!(
            clean_title("15 Minutes (2001) MULTI"),
            "15 Minutes (2001) MULTI"
        );
        assert_eq!(clean_title("Dune (2021) MULTI 4K HDR"), "Dune (2021) MULTI");
        assert_eq!(clean_title("Dune (2021) FHD MULTI"), "Dune (2021) MULTI");
        assert_eq!(clean_title("Archer (2009) (VOST)"), "Archer (2009) (VOST)");
        assert_eq!(
            clean_title("Archer (2009) FHD (VOST)"),
            "Archer (2009) (VOST)"
        );
        assert_eq!(clean_title("Dune VOSTFR 1080p"), "Dune VOSTFR");
        // Le français en tête part toujours, MULTI ne part pas.
        assert_eq!(
            clean_title("FR - Affection (2026) MULTI"),
            "Affection (2026) MULTI"
        );
    }

    #[test]
    fn titles_made_only_of_mentions_or_looking_like_words_stay() {
        assert_eq!(clean_title("HD"), "HD");
        assert_eq!(clean_title("4Kids TV"), "4Kids TV");
        assert_eq!(clean_title("HDTV Show"), "HDTV Show");
        assert_eq!(clean_title("Apollo 13"), "Apollo 13");
        assert_eq!(clean_title("EN - Dune (2021)"), "EN - Dune (2021)");
        assert_eq!(clean_title("MULTI"), "MULTI");
    }

    #[test]
    fn release_file_names_become_titles() {
        assert_eq!(
            clean_title("15.Aout.2001.1080p.TF1.WEB-DL.AAC"),
            "15 Aout (2001)"
        );
        assert_eq!(
            clean_title("Expendables.4.2014.1080p.TF1.WEB-DL"),
            "Expendables 4 (2014)"
        );
        // Plusieurs années de suite : la dernière avant la mention technique.
        assert_eq!(clean_title("1917.2019.2160p.UHD"), "1917 (2019)");
        assert_eq!(
            clean_title("Blade.Runner.2049.2017.1080p"),
            "Blade Runner 2049 (2017)"
        );
        assert_eq!(
            clean_title("Dune.2021.MULTI.1080p.WEB-DL"),
            "Dune (2021) MULTI"
        );
        assert_eq!(
            clean_title("Le_Dernier_Duel_2021_1080p"),
            "Le Dernier Duel (2021)"
        );
        // Sans année : le titre s'arrête à la première mention.
        assert_eq!(clean_title("Some.Movie.1080p.WEB-DL"), "Some Movie");
        // Sans mention : l'année suffit.
        assert_eq!(clean_title("Dr.Strangelove.1964"), "Dr Strangelove (1964)");
    }

    #[test]
    fn names_that_are_not_file_names_stay() {
        assert_eq!(clean_title("S.W.A.T."), "S.W.A.T.");
        assert_eq!(clean_title("Mr.Robot"), "Mr.Robot");
        assert_eq!(
            clean_title("L.A. Confidential (1997)"),
            "L.A. Confidential (1997)"
        );
        assert_eq!(
            clean_title("Ocean's Eleven (2001)"),
            "Ocean's Eleven (2001)"
        );
        // Une mention en tête ne laisse aucun titre : rien ne change.
        assert_eq!(
            clean_title("1080p.Some.Movie.2020"),
            "1080p.Some.Movie.2020"
        );
    }

    #[test]
    fn channel_names_keep_their_mentions() {
        // Les chaînes ne passent pas par `clean_title`.
        assert_eq!(clean_name("TF1 ᵁᴴᴰ"), "TF1 ᵁᴴᴰ");
        assert_eq!(clean_name("FRANCE 3 FHD"), "FRANCE 3 FHD");
        assert_eq!(clean_name("|FR| canal+ FOOT UHD"), "canal+ FOOT UHD");
    }

    #[test]
    fn titles_stay_searchable_by_what_they_lost() {
        assert_eq!(search_name("Dune (2021) FHD HDR"), "Dune (2021) FHD HDR");
        assert_eq!(
            search_name("15.Aout.2001.1080p.TF1.WEB-DL"),
            "15.Aout.2001.1080p.TF1.WEB-DL"
        );
    }

    #[test]
    fn the_year_goes_only_when_the_sheet_shows_it() {
        assert_eq!(
            title_without_year("22 Jump Street (2014)", "2014"),
            "22 Jump Street"
        );
        assert_eq!(
            title_without_year("22 Jump Street (2014)", "2014-06-13"),
            "22 Jump Street"
        );
        assert_eq!(
            title_without_year("15 Minutes (2001) MULTI", "2001"),
            "15 Minutes MULTI"
        );
        // Une autre année, ou aucune : le titre reste.
        assert_eq!(title_without_year("Dune (1984)", "2021"), "Dune (1984)");
        assert_eq!(title_without_year("Dune", "2021"), "Dune");
        assert_eq!(title_without_year("Dune (2021)", ""), "Dune (2021)");
        assert_eq!(title_without_year("Dune (2021)", "n/a"), "Dune (2021)");
        // Un titre qui n'est que l'année la garde.
        assert_eq!(title_without_year("(2014)", "2014"), "(2014)");
    }
}

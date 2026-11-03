//! Choix des pistes audio et des sous-titres d'un film ou d'un épisode.
//!
//! Un réglage à trois valeurs (décision du 2026-11-03), propre à
//! l'abonnement : [`TrackPreference::Auto`] laisse la piste par défaut du
//! fichier ; [`TrackPreference::Dubbed`] prend la piste dans la langue de
//! l'app, sans sous-titres ; [`TrackPreference::OriginalSubtitled`] prend la
//! première piste dans une autre langue, avec les sous-titres dans la langue
//! de l'app. Le lecteur applique le choix une fois au lancement ; un choix à
//! la main ensuite l'emporte. Pas pour le direct.
//!
//! Les fournisseurs disent la langue de façon très inégale : un code
//! (« fr », « fra », « fre », « fr-CA »), un libellé (« VFF », « Français »,
//! « TRUEFRENCH »), ou rien (« und »). [`choose_tracks`] lit les deux.

use crate::catalog::Catalog;
use crate::error::Result;

const PREFERENCE_KEY: &str = "track_preference";

/// Réglage de langue des films et des épisodes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TrackPreference {
    /// Piste par défaut du fichier.
    #[default]
    Auto,
    /// Piste dans la langue de l'app, sans sous-titres.
    Dubbed,
    /// Piste d'origine, avec les sous-titres dans la langue de l'app.
    OriginalSubtitled,
}

impl TrackPreference {
    fn key(self) -> &'static str {
        match self {
            TrackPreference::Auto => "auto",
            TrackPreference::Dubbed => "dubbed",
            TrackPreference::OriginalSubtitled => "original",
        }
    }

    fn from_key(key: &str) -> Self {
        match key {
            "dubbed" => TrackPreference::Dubbed,
            "original" => TrackPreference::OriginalSubtitled,
            _ => TrackPreference::Auto,
        }
    }
}

/// Une piste audio ou de sous-titres, telle que le lecteur la décrit.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TrackInfo {
    /// Code de langue du fichier (« fr », « fra », « fr-CA »…), s'il y en a un.
    pub language: Option<String>,
    /// Libellé du fichier (« VFF », « Français 5.1 »…).
    pub label: Option<String>,
    /// Sous-titres « forcés » (dialogues étrangers seulement).
    pub forced: bool,
}

/// Pistes à sélectionner. Les numéros sont ceux des listes données à
/// [`choose_tracks`] ; rien n'est demandé quand tout est `None` et `false`.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TrackChoice {
    /// Piste audio à prendre ; `None` : celle par défaut.
    pub audio: Option<usize>,
    /// Sous-titres à prendre ; `None` : ceux par défaut (ou aucun, voir `text_off`).
    pub text: Option<usize>,
    /// Couper les sous-titres.
    pub text_off: bool,
}

impl Catalog {
    /// Réglage de langue ; [`TrackPreference::Auto`] tant qu'il n'a pas été choisi.
    pub fn track_preference(&self) -> Result<TrackPreference> {
        Ok(self
            .setting(PREFERENCE_KEY)?
            .as_deref()
            .map(TrackPreference::from_key)
            .unwrap_or_default())
    }

    pub fn set_track_preference(&self, preference: TrackPreference) -> Result<()> {
        self.set_setting(PREFERENCE_KEY, preference.key())
    }
}

/// Langues reconnues : code à deux lettres, codes à trois lettres, et mots
/// d'étiquette (sans accents, en minuscules).
struct Language {
    code: &'static str,
    alt: &'static [&'static str],
    words: &'static [&'static str],
}

const LANGUAGES: &[Language] = &[
    Language {
        code: "fr",
        alt: &["fra", "fre"],
        words: &[
            "francais",
            "french",
            "vf",
            "vff",
            "vfq",
            "vfi",
            "truefrench",
            "vostfr",
        ],
    },
    Language {
        code: "en",
        alt: &["eng"],
        words: &["english", "anglais"],
    },
    Language {
        code: "it",
        alt: &["ita"],
        words: &["italiano", "italian", "italien"],
    },
    Language {
        code: "es",
        alt: &["spa"],
        words: &["espanol", "spanish", "espagnol", "castellano"],
    },
    Language {
        code: "de",
        alt: &["deu", "ger"],
        words: &["deutsch", "german", "allemand"],
    },
    Language {
        code: "pt",
        alt: &["por"],
        words: &["portugues", "portuguese", "portugais"],
    },
    Language {
        code: "ar",
        alt: &["ara"],
        words: &["arabic", "arabe"],
    },
    Language {
        code: "tr",
        alt: &["tur"],
        words: &["turkce", "turkish", "turc"],
    },
    Language {
        code: "nl",
        alt: &["nld", "dut"],
        words: &["nederlands", "dutch", "neerlandais"],
    },
    Language {
        code: "ja",
        alt: &["jpn"],
        words: &["japanese", "japonais"],
    },
    Language {
        code: "ko",
        alt: &["kor"],
        words: &["korean", "coreen"],
    },
    Language {
        code: "ru",
        alt: &["rus"],
        words: &["russian", "russe"],
    },
    Language {
        code: "pl",
        alt: &["pol"],
        words: &["polski", "polish", "polonais"],
    },
    Language {
        code: "zh",
        alt: &["zho", "chi"],
        words: &["chinese", "chinois", "mandarin"],
    },
    Language {
        code: "hi",
        alt: &["hin"],
        words: &["hindi"],
    },
];

/// Langue d'une piste.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Lang {
    /// Langue reconnue (code à deux lettres).
    Known(&'static str),
    /// Un code de langue que la liste ne connaît pas : une autre langue.
    Other,
    /// Rien d'exploitable (« und », code absent, libellé muet).
    Unknown,
}

/// Mots d'un libellé, en minuscules et sans accents : « Français (FR) 5.1 »
/// donne `francais`, `fr`, `5`, `1`.
fn label_words(label: &str) -> Vec<String> {
    let mut words = Vec::new();
    let mut current = String::new();
    for c in label.chars().flat_map(char::to_lowercase) {
        let c = match c {
            'à' | 'á' | 'â' | 'ä' => 'a',
            'ç' => 'c',
            'è' | 'é' | 'ê' | 'ë' => 'e',
            'ì' | 'í' | 'î' | 'ï' => 'i',
            'ñ' => 'n',
            'ò' | 'ó' | 'ô' | 'ö' => 'o',
            'ù' | 'ú' | 'û' | 'ü' => 'u',
            other => other,
        };
        if c.is_alphanumeric() {
            current.push(c);
        } else if !current.is_empty() {
            words.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        words.push(current);
    }
    words
}

/// Première partie d'une étiquette de langue, en minuscules : « fr-CA » et
/// « FR_ca » donnent « fr ».
fn primary(tag: &str) -> String {
    tag.trim()
        .split(['-', '_'])
        .next()
        .unwrap_or_default()
        .to_ascii_lowercase()
}

fn find_language(code: &str) -> Option<&'static Language> {
    LANGUAGES
        .iter()
        .find(|l| l.code == code || l.alt.contains(&code))
}

/// Code à deux lettres de la langue de l'app : « fr », « fra » et « FR-ca »
/// donnent « fr ». Une langue inconnue est gardée telle quelle.
fn canonical(tag: &str) -> String {
    let code = primary(tag);
    find_language(&code).map_or(code, |l| l.code.to_owned())
}

fn language_of(track: &TrackInfo) -> Lang {
    if let Some(tag) = track.language.as_deref() {
        let code = primary(tag);
        if !code.is_empty() && !matches!(code.as_str(), "und" | "zxx" | "mul" | "mis") {
            return find_language(&code).map_or(Lang::Other, |l| Lang::Known(l.code));
        }
    }
    let words = label_words(track.label.as_deref().unwrap_or_default());
    LANGUAGES
        .iter()
        .find(|l| words.iter().any(|w| l.words.contains(&w.as_str())))
        .map_or(Lang::Unknown, |l| Lang::Known(l.code))
}

fn has_word(track: &TrackInfo, wanted: &[&str]) -> bool {
    label_words(track.label.as_deref().unwrap_or_default())
        .iter()
        .any(|w| wanted.contains(&w.as_str()))
}

fn is_forced(track: &TrackInfo) -> bool {
    track.forced || has_word(track, &["forced", "force", "forces"])
}

fn in_language(track: &TrackInfo, app: &str) -> bool {
    language_of(track) == Lang::Known(app_code(app))
}

/// `Lang::Known` porte un `&'static str` : retrouve celui de la langue de l'app.
fn app_code(app: &str) -> &'static str {
    find_language(app).map_or("", |l| l.code)
}

/// Autre langue que celle de l'app, connue ou non de la liste.
fn in_other_language(track: &TrackInfo, app: &str) -> bool {
    match language_of(track) {
        Lang::Known(code) => code != app_code(app),
        Lang::Other => true,
        Lang::Unknown => false,
    }
}

/// Piste d'origine annoncée par son libellé (« VO », « Original »). « VOSTFR »
/// aussi : sur une piste audio, c'est la langue d'origine (les sous-titres
/// français sont à part), même quand le flux la dit française.
fn says_original(track: &TrackInfo) -> bool {
    has_word(
        track,
        &["vo", "ov", "original", "originale", "vost", "vostfr"],
    )
}

/// Rang d'une piste audio dans la langue de l'app : 0 la meilleure.
/// « VFF » avant les autres, « VFQ » et « VFI » après, audiodescription et
/// commentaires en dernier.
fn audio_rank(track: &TrackInfo) -> u8 {
    if has_word(
        track,
        &[
            "audiodescription",
            "ad",
            "description",
            "descriptive",
            "commentary",
            "commentaire",
        ],
    ) {
        3
    } else if has_word(track, &["vfq", "vfi"]) {
        2
    } else if has_word(track, &["vff"]) {
        0
    } else {
        1
    }
}

/// Rang de sous-titres complets : les « malentendants » après les autres.
fn text_rank(track: &TrackInfo) -> u8 {
    u8::from(has_word(
        track,
        &["sdh", "cc", "hoh", "malentendants", "deaf"],
    ))
}

/// Le premier de rang le plus bas parmi ceux qui conviennent.
fn best(
    tracks: &[TrackInfo],
    fits: impl Fn(&TrackInfo) -> bool,
    rank: fn(&TrackInfo) -> u8,
) -> Option<usize> {
    tracks
        .iter()
        .enumerate()
        .filter(|(_, track)| fits(track))
        .min_by_key(|(_, track)| rank(track))
        .map(|(index, _)| index)
}

/// Pistes à prendre selon le réglage. `app_language` : langue de l'app (« fr »,
/// « en »…). Quand rien ne convient (aucune piste doublée, aucune piste
/// d'origine), le résultat ne demande rien : la piste par défaut reste.
pub fn choose_tracks(
    preference: TrackPreference,
    app_language: &str,
    audio: &[TrackInfo],
    text: &[TrackInfo],
) -> TrackChoice {
    let app = canonical(app_language);
    match preference {
        TrackPreference::Auto => TrackChoice::default(),
        TrackPreference::Dubbed => {
            // Une piste « VOSTFR » est l'original, pas une version doublée.
            let Some(dubbed) = best(
                audio,
                |t| in_language(t, &app) && !says_original(t),
                audio_rank,
            ) else {
                return TrackChoice::default();
            };
            // Aucun sous-titre, sauf les « forcés » de la langue (dialogues
            // étrangers) : ils servent même une version doublée.
            let forced = text
                .iter()
                .position(|t| is_forced(t) && in_language(t, &app));
            TrackChoice {
                audio: Some(dubbed),
                text: forced,
                text_off: forced.is_none(),
            }
        }
        TrackPreference::OriginalSubtitled => {
            // L'original annoncé (« VO ») passe avant la première langue
            // étrangère : avec [fra, ita, eng « VO »], c'est l'anglais, pas la
            // version italienne. Audiodescription et commentaires, jamais.
            let original = audio
                .iter()
                .position(|t| says_original(t) && audio_rank(t) < 3)
                .or_else(|| {
                    audio
                        .iter()
                        .position(|t| in_other_language(t, &app) && audio_rank(t) < 3)
                });
            let Some(original) = original else {
                return TrackChoice::default();
            };
            // Sous-titres complets de préférence ; les « forcés » seuls si l'on
            // n'a rien d'autre ; sinon ceux par défaut restent.
            let subtitles = best(text, |t| in_language(t, &app) && !is_forced(t), text_rank)
                .or_else(|| text.iter().position(|t| in_language(t, &app)));
            TrackChoice {
                audio: Some(original),
                text: subtitles,
                text_off: false,
            }
        }
    }
}

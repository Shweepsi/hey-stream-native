//! Fiches détaillées : film (`get_vod_info`) et série avec ses saisons et
//! épisodes (`get_series_info`). Demandées à l'ouverture d'une fiche,
//! jamais en masse.

use serde::Deserialize;
use serde_json::Value;

use crate::client::XtreamClient;
use crate::error::{Error, Result};
use crate::lenient::{list, opt_i64, opt_string, required_i64};

/// Fiche d'un film.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct MovieInfo {
    pub name: Option<String>,
    pub plot: Option<String>,
    pub year: Option<String>,
    pub duration_secs: Option<i64>,
    pub rating: Option<String>,
    pub image: Option<String>,
    pub extension: Option<String>,
}

/// Fiche d'une série, saisons triées et épisodes triés.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct SeriesInfo {
    pub name: Option<String>,
    pub plot: Option<String>,
    pub year: Option<String>,
    pub rating: Option<String>,
    pub cover: Option<String>,
    pub seasons: Vec<Season>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Season {
    pub number: i64,
    pub name: Option<String>,
    pub episodes: Vec<Episode>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Episode {
    /// Identifiant de lecture (`/series/u/p/{id}.{ext}`).
    pub id: i64,
    pub season: i64,
    pub number: Option<i64>,
    pub title: Option<String>,
    pub extension: Option<String>,
    pub plot: Option<String>,
    pub image: Option<String>,
    pub duration_secs: Option<i64>,
}

impl SeriesInfo {
    /// Épisode qui suit `episode_id`, dans la même saison ou au début de la
    /// suivante (lecture automatique de l'épisode suivant).
    pub fn next_episode(&self, episode_id: i64) -> Option<&Episode> {
        let mut all = self.seasons.iter().flat_map(|s| s.episodes.iter());
        all.find(|e| e.id == episode_id)?;
        all.next()
    }
}

#[derive(Deserialize, Default)]
struct RawInfo {
    #[serde(default, deserialize_with = "opt_string")]
    name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    title: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    plot: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    description: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    releasedate: Option<String>,
    #[serde(default, rename = "releaseDate", deserialize_with = "opt_string")]
    release_date: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    year: Option<String>,
    #[serde(default, deserialize_with = "opt_i64")]
    duration_secs: Option<i64>,
    #[serde(default, deserialize_with = "opt_string")]
    duration: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    rating: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    movie_image: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    cover: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    cover_big: Option<String>,
}

/// Plus grand nombre seul lu comme des minutes (10 h) ; au-delà, des secondes.
const BARE_MINUTES_MAX: i64 = 600;

impl RawInfo {
    fn plot(&self) -> Option<String> {
        self.plot.clone().or_else(|| self.description.clone())
    }

    /// Année : champ `year`, sinon les 4 premiers chiffres de la date de sortie.
    fn year(&self) -> Option<String> {
        self.year
            .iter()
            .chain(&self.releasedate)
            .chain(&self.release_date)
            .find_map(|text| {
                let digits: String = text.chars().take_while(char::is_ascii_digit).collect();
                (digits.len() == 4).then_some(digits)
            })
    }

    /// Durée en secondes : `duration_secs`, sinon `duration` au format
    /// `HH:MM:SS` ou `MM:SS`. Un nombre seul est ambigu : jusqu'à 600, ce
    /// sont des minutes (« 109 » : un film d'1 h 49, pas 2 min) ; au-delà,
    /// des secondes (« 6540 » : 1 h 49, pas 109 h).
    fn duration_secs(&self) -> Option<i64> {
        self.duration_secs.filter(|&d| d > 0).or_else(|| {
            let text = self.duration.as_deref()?.trim();
            let parts: Option<Vec<i64>> = text.split(':').map(|p| p.trim().parse().ok()).collect();
            let parts = parts?;
            let secs = match parts.as_slice() {
                [n] if *n <= BARE_MINUTES_MAX => n * 60,
                [secs] => *secs,
                _ => parts.iter().fold(0, |acc, p| acc * 60 + p),
            };
            (secs > 0).then_some(secs)
        })
    }

    fn image(&self) -> Option<String> {
        self.movie_image
            .clone()
            .or_else(|| self.cover_big.clone())
            .or_else(|| self.cover.clone())
    }
}

#[derive(Deserialize)]
struct RawMovieData {
    #[serde(default, deserialize_with = "opt_string")]
    name: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    container_extension: Option<String>,
}

#[derive(Deserialize)]
struct RawSeason {
    #[serde(default, deserialize_with = "opt_i64")]
    season_number: Option<i64>,
    #[serde(default, deserialize_with = "opt_string")]
    name: Option<String>,
}

#[derive(Deserialize)]
struct RawEpisode {
    #[serde(deserialize_with = "required_i64")]
    id: i64,
    #[serde(default, deserialize_with = "opt_i64")]
    episode_num: Option<i64>,
    #[serde(default, deserialize_with = "opt_i64")]
    season: Option<i64>,
    #[serde(default, deserialize_with = "opt_string")]
    title: Option<String>,
    #[serde(default, deserialize_with = "opt_string")]
    container_extension: Option<String>,
    #[serde(default)]
    info: Value,
}

/// `info` est un objet, mais certains panneaux envoient `[]` ou `null`.
fn raw_info(value: Option<Value>) -> RawInfo {
    value
        .filter(Value::is_object)
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_default()
}

impl XtreamClient {
    /// Fiche d'un film.
    pub async fn movie_info(&self, stream_id: i64) -> Result<MovieInfo> {
        let id = stream_id.to_string();
        let value = self
            .get(Some("get_vod_info"), &[("vod_id", id.as_str())])
            .await?;
        let Value::Object(mut map) = value else {
            return Err(Error::InvalidResponse);
        };
        let info = raw_info(map.remove("info"));
        let data: Option<RawMovieData> = map
            .remove("movie_data")
            .and_then(|v| serde_json::from_value(v).ok());
        Ok(MovieInfo {
            name: data
                .as_ref()
                .and_then(|d| d.name.clone())
                .or(info.name.clone()),
            plot: info.plot(),
            year: info.year(),
            duration_secs: info.duration_secs(),
            rating: info.rating.clone(),
            image: info.image(),
            extension: data.and_then(|d| d.container_extension),
        })
    }

    /// Fiche d'une série avec toutes ses saisons et tous ses épisodes.
    pub async fn series_info(&self, series_id: i64) -> Result<SeriesInfo> {
        let id = series_id.to_string();
        let value = self
            .get(Some("get_series_info"), &[("series_id", id.as_str())])
            .await?;
        let Value::Object(mut map) = value else {
            return Err(Error::InvalidResponse);
        };
        let info = raw_info(map.remove("info"));
        let raw_seasons: Vec<RawSeason> = list(map.remove("seasons").unwrap_or(Value::Null));

        // `episodes` : objet { "1": [...], "2": [...] } ou tableau de tableaux.
        let groups: Vec<(Option<i64>, Value)> = match map.remove("episodes") {
            Some(Value::Object(by_season)) => by_season
                .into_iter()
                .map(|(key, eps)| (key.trim().parse().ok(), eps))
                .collect(),
            Some(Value::Array(groups)) => groups.into_iter().map(|eps| (None, eps)).collect(),
            _ => Vec::new(),
        };

        let mut seasons: Vec<Season> = Vec::new();
        for (key, eps) in groups {
            for raw in list::<RawEpisode>(eps) {
                let ep_info = raw_info(Some(raw.info));
                let number = raw.season.or(key).unwrap_or(1);
                let episode = Episode {
                    id: raw.id,
                    season: number,
                    number: raw.episode_num,
                    title: raw.title,
                    extension: raw.container_extension,
                    plot: ep_info.plot(),
                    image: ep_info.image(),
                    duration_secs: ep_info.duration_secs(),
                };
                match seasons.iter_mut().find(|s| s.number == number) {
                    Some(season) => season.episodes.push(episode),
                    None => seasons.push(Season {
                        number,
                        name: raw_seasons
                            .iter()
                            .find(|s| s.season_number == Some(number))
                            .and_then(|s| s.name.clone()),
                        episodes: vec![episode],
                    }),
                }
            }
        }
        seasons.sort_by_key(|s| s.number);
        for season in &mut seasons {
            season
                .episodes
                .sort_by_key(|e| (e.number.unwrap_or(i64::MAX), e.id));
            season.episodes.dedup_by_key(|e| e.id);
        }

        Ok(SeriesInfo {
            name: info.name.clone().or(info.title.clone()),
            plot: info.plot(),
            year: info.year(),
            rating: info.rating.clone(),
            cover: info.image(),
            seasons,
        })
    }
}

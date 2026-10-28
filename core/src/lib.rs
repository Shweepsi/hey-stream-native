//! Cœur Xtream Codes de hey-stream-native.
//!
//! Toute la logique métier vit ici ; les interfaces Android (Compose) et
//! Apple (SwiftUI) ne font qu'afficher. Le module est volontairement
//! tolérant : les panneaux Xtream renvoient souvent des nombres en texte,
//! `null` au lieu d'une liste vide, ou des entrées incomplètes. Une entrée
//! illisible est ignorée, jamais fatale pour toute la liste.

mod account;
mod catalog;
mod client;
mod details;
mod epg;
mod error;
mod lenient;
mod model;
mod parental;
mod url;
mod user_data;

pub use account::{AccountKey, AccountMatch, CachedAccount};
pub use catalog::{
    CachedCategory, Catalog, CatalogItem, MAX_AGE_SECS, RefreshOutcome, Section, SectionData,
    fetch_section, refresh_section,
};
pub use client::{USER_AGENT, XtreamClient};
pub use details::{Episode, MovieInfo, Season, SeriesInfo};
pub use epg::{EPG_MIN_INTERVAL_SECS, NowNext, Programme, now_next_for};
pub use error::{Error, Result};
pub use model::{AccountInfo, Category, Credentials, LiveStream, Movie, Series, ServerInfo};
pub use url::{LiveFormat, StreamKind, normalize_server_url};
pub use user_data::{PlaybackKind, RECENTS_LIMIT, Resume, ResumeEntry};

//! Cœur Xtream Codes de hey-stream-native.
//!
//! Toute la logique métier vit ici ; les interfaces Android (Compose) et
//! Apple (SwiftUI) ne font qu'afficher. Le module est volontairement
//! tolérant : les panneaux Xtream renvoient souvent des nombres en texte,
//! `null` au lieu d'une liste vide, ou des entrées incomplètes. Une entrée
//! illisible est ignorée, jamais fatale pour toute la liste.

/// Condition SQL sur une reprise `r` : pour un épisode, être plus récente que
/// le dernier épisode de sa série vu jusqu'au bout (sinon ce n'est plus « le
/// dernier épisode regardé »). Macro pour l'insérer dans des requêtes `const`.
macro_rules! not_superseded {
    () => {
        "(r.series_id IS NULL OR r.updated_at > COALESCE(
            (SELECT MAX(w.watched_at) FROM watched w
             WHERE w.series_id = r.series_id AND w.kind = 'episode' AND w.ended = 1), -1))"
    };
}

mod account;
mod catalog;
mod client;
mod details;
mod epg;
mod error;
mod lenient;
mod model;
mod names;
mod parental;
mod recording;
mod telemetry;
mod update;
mod url;
mod user_data;

pub use account::{AccountKey, AccountMatch, CachedAccount};
pub use catalog::{
    CachedCategory, Catalog, CatalogItem, MAX_AGE_SECS, Progress, RefreshOutcome, Section,
    SectionData, fetch_section, refresh_section,
};
pub use client::{USER_AGENT, XtreamClient};
pub use details::{Episode, MovieInfo, Season, SeriesInfo};
pub use epg::{EPG_MIN_INTERVAL_SECS, LATER_MAX, NowNext, Programme, now_next_for};
pub use error::{Error, Result};
pub use model::{AccountInfo, Category, Credentials, LiveStream, Movie, Series, ServerInfo};
pub use names::clean_name;
pub use recording::{
    RecordingSummary, SCRUBBED_HOST, clear_recording, export_recording, recording_summary,
};
pub use telemetry::{EventLog, TelemetryKind, TelemetryTarget, UploadOutcome, upload_telemetry};
pub use update::{
    AppUpdate, GITHUB_API, TESTER_APK_SUFFIX, UPDATE_REPO, check_tester_update, check_update,
    download_update,
};
pub use url::{LiveFormat, StreamKind, describe_stream_url, normalize_server_url};
pub use user_data::{PlaybackKind, RECENTS_LIMIT, Resume, ResumeEntry};

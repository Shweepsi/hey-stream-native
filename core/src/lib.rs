//! Cœur Xtream Codes de hey-stream-native.
//!
//! Toute la logique métier vit ici ; les interfaces Android (Compose) et
//! Apple (SwiftUI) ne font qu'afficher. Le module est volontairement
//! tolérant : les panneaux Xtream renvoient souvent des nombres en texte,
//! `null` au lieu d'une liste vide, ou des entrées incomplètes. Une entrée
//! illisible est ignorée, jamais fatale pour toute la liste.

mod catalog;
mod client;
mod error;
mod lenient;
mod model;
mod url;

pub use catalog::{
    CachedCategory, Catalog, CatalogItem, MAX_AGE_SECS, RefreshOutcome, Section, refresh_section,
};
pub use client::XtreamClient;
pub use error::{Error, Result};
pub use model::{AccountInfo, Category, Credentials, LiveStream, Movie, Series, ServerInfo};
pub use url::{StreamKind, normalize_server_url};

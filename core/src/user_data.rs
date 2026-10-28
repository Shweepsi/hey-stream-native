//! Données de l'utilisateur : favoris, récents et reprise de lecture.
//!
//! Elles vivent dans des tables à part, indexées par l'identifiant du
//! fournisseur : un rafraîchissement du catalogue ne les efface pas. Une
//! entrée qui disparaît du catalogue est simplement masquée, et réapparaît si
//! le fournisseur la remet.

use rusqlite::{OptionalExtension, params};

use crate::catalog::{Catalog, CatalogItem, ITEM_COLUMNS, Section, row_to_item};
use crate::error::Result;

pub(crate) const MIGRATION: &str = "
    CREATE TABLE favorite (
        section TEXT NOT NULL,
        id INTEGER NOT NULL,
        added_at INTEGER NOT NULL,
        PRIMARY KEY (section, id)
    );
    CREATE TABLE recent (
        section TEXT NOT NULL,
        id INTEGER NOT NULL,
        watched_at INTEGER NOT NULL,
        PRIMARY KEY (section, id)
    );
    CREATE TABLE resume (
        kind TEXT NOT NULL,
        id INTEGER NOT NULL,
        position_ms INTEGER NOT NULL,
        duration_ms INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        title TEXT NOT NULL,
        image TEXT,
        extension TEXT,
        series_id INTEGER,
        PRIMARY KEY (kind, id)
    );
    CREATE INDEX resume_by_date ON resume (updated_at);
";

/// Progression des séries dans les listes ; films, épisodes et séries vus.
pub(crate) const MIGRATION_4: &str = "
    CREATE INDEX resume_by_series ON resume (series_id, updated_at);
    CREATE TABLE watched (
        kind TEXT NOT NULL,
        id INTEGER NOT NULL,
        series_id INTEGER,
        watched_at INTEGER NOT NULL,
        PRIMARY KEY (kind, id)
    );
";

/// Nombre de récents gardés par section.
pub const RECENTS_LIMIT: usize = 30;

/// En dessous de 30 s de visionnage, rien n'est retenu.
const MIN_POSITION_MS: i64 = 30_000;
/// Au-delà de 95 % (ou à moins de 2 min de la fin), le contenu est terminé.
const FINISHED_RATIO: f64 = 0.95;
const FINISHED_REMAINING_MS: i64 = 120_000;

/// Contenu dont on peut reprendre la lecture. Le direct n'en fait pas partie.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaybackKind {
    Movie,
    Episode,
}

impl PlaybackKind {
    fn key(self) -> &'static str {
        match self {
            PlaybackKind::Movie => "movie",
            PlaybackKind::Episode => "episode",
        }
    }

    fn from_key(key: &str) -> Option<Self> {
        match key {
            "movie" => Some(PlaybackKind::Movie),
            "episode" => Some(PlaybackKind::Episode),
            _ => None,
        }
    }
}

/// Ce que le lecteur rapporte pendant la lecture. Le titre et l'image sont
/// gardés pour afficher la rangée « Continuer » sans requête réseau (les
/// épisodes ne sont pas dans le cache du catalogue).
#[derive(Debug, Clone, PartialEq)]
pub struct Resume {
    pub kind: PlaybackKind,
    /// `stream_id` d'un film, identifiant d'épisode pour une série.
    pub id: i64,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub title: String,
    pub image: Option<String>,
    pub extension: Option<String>,
    /// Série parente d'un épisode.
    pub series_id: Option<i64>,
}

/// Entrée de la rangée « Continuer ».
#[derive(Debug, Clone, PartialEq)]
pub struct ResumeEntry {
    pub resume: Resume,
    pub updated_at: i64,
}

impl Resume {
    fn is_finished(&self) -> bool {
        self.duration_ms > 0
            && (self.position_ms as f64 >= self.duration_ms as f64 * FINISHED_RATIO
                || self.duration_ms - self.position_ms <= FINISHED_REMAINING_MS)
    }
}

impl Catalog {
    // --- Favoris -----------------------------------------------------------

    /// Ajoute ou retire un favori ; renvoie le nouvel état.
    pub fn toggle_favorite(&mut self, section: Section, id: i64, now: i64) -> Result<bool> {
        let removed = self.conn.execute(
            "DELETE FROM favorite WHERE section = ?1 AND id = ?2",
            params![section.key(), id],
        )?;
        if removed > 0 {
            return Ok(false);
        }
        self.conn.execute(
            "INSERT INTO favorite (section, id, added_at) VALUES (?1, ?2, ?3)",
            params![section.key(), id, now],
        )?;
        Ok(true)
    }

    pub fn is_favorite(&self, section: Section, id: i64) -> Result<bool> {
        Ok(self
            .conn
            .query_row(
                "SELECT 1 FROM favorite WHERE section = ?1 AND id = ?2",
                params![section.key(), id],
                |_| Ok(()),
            )
            .optional()?
            .is_some())
    }

    /// Favoris présents dans le catalogue, du plus récent au plus ancien.
    pub fn favorites(
        &self,
        section: Section,
        offset: usize,
        limit: usize,
    ) -> Result<Vec<CatalogItem>> {
        self.joined_items(
            &format!(
                "SELECT {ITEM_COLUMNS}
                 FROM favorite f JOIN item i ON i.section = f.section AND i.id = f.id
                 WHERE f.section = ?1{} ORDER BY f.added_at DESC, f.id LIMIT ?2 OFFSET ?3",
                self.visibility("i", false)
            ),
            section,
            offset,
            limit,
        )
    }

    // --- Récents -----------------------------------------------------------

    /// Note qu'une chaîne, un film ou une série vient d'être regardé. Seuls les
    /// `RECENTS_LIMIT` derniers de la section sont gardés.
    pub fn mark_watched(&mut self, section: Section, id: i64, now: i64) -> Result<()> {
        let tx = self.conn.transaction()?;
        tx.execute(
            "INSERT OR REPLACE INTO recent (section, id, watched_at) VALUES (?1, ?2, ?3)",
            params![section.key(), id, now],
        )?;
        tx.execute(
            "DELETE FROM recent WHERE section = ?1 AND id NOT IN (
                SELECT id FROM recent WHERE section = ?1
                ORDER BY watched_at DESC, id LIMIT ?2)",
            params![section.key(), RECENTS_LIMIT as i64],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// Récents présents dans le catalogue, du plus récent au plus ancien.
    pub fn recents(&self, section: Section, limit: usize) -> Result<Vec<CatalogItem>> {
        self.joined_items(
            &format!(
                "SELECT {ITEM_COLUMNS}
                 FROM recent r JOIN item i ON i.section = r.section AND i.id = r.id
                 WHERE r.section = ?1{} ORDER BY r.watched_at DESC, r.id LIMIT ?2 OFFSET ?3",
                self.visibility("i", false)
            ),
            section,
            0,
            limit,
        )
    }

    // --- Reprise de lecture -----------------------------------------------

    /// Enregistre la position de lecture. Moins de 30 s : rien n'est retenu.
    /// Contenu terminé : l'entrée est retirée de « Continuer ».
    pub fn save_progress(&mut self, resume: &Resume, now: i64) -> Result<()> {
        if resume.is_finished() {
            return self.set_watched(resume.kind, resume.id, resume.series_id, true, now);
        }
        if resume.position_ms < MIN_POSITION_MS {
            return Ok(());
        }
        self.conn.execute(
            "INSERT OR REPLACE INTO resume
                (kind, id, position_ms, duration_ms, updated_at, title, image, extension, series_id)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                resume.kind.key(),
                resume.id,
                resume.position_ms,
                resume.duration_ms,
                now,
                resume.title,
                resume.image,
                resume.extension,
                resume.series_id,
            ],
        )?;
        Ok(())
    }

    pub fn clear_progress(&mut self, kind: PlaybackKind, id: i64) -> Result<()> {
        self.conn.execute(
            "DELETE FROM resume WHERE kind = ?1 AND id = ?2",
            params![kind.key(), id],
        )?;
        Ok(())
    }

    // --- Vus -----------------------------------------------------------------

    /// Marque un film ou un épisode comme vu (fin de lecture, ou à la main) ou
    /// non vu. Vu, il quitte « Continuer » ; le revoir ne retire pas la marque.
    pub fn set_watched(
        &mut self,
        kind: PlaybackKind,
        id: i64,
        series_id: Option<i64>,
        watched: bool,
        now: i64,
    ) -> Result<()> {
        if watched {
            let tx = self.conn.transaction()?;
            tx.execute(
                "INSERT OR REPLACE INTO watched (kind, id, series_id, watched_at)
                 VALUES (?1, ?2, ?3, ?4)",
                params![kind.key(), id, series_id, now],
            )?;
            tx.execute(
                "DELETE FROM resume WHERE kind = ?1 AND id = ?2",
                params![kind.key(), id],
            )?;
            tx.commit()?;
        } else {
            self.conn.execute(
                "DELETE FROM watched WHERE kind = ?1 AND id = ?2",
                params![kind.key(), id],
            )?;
        }
        Ok(())
    }

    pub fn is_watched(&self, kind: PlaybackKind, id: i64) -> Result<bool> {
        Ok(self
            .conn
            .query_row(
                "SELECT 1 FROM watched WHERE kind = ?1 AND id = ?2",
                params![kind.key(), id],
                |_| Ok(()),
            )
            .optional()?
            .is_some())
    }

    /// Recalcule la marque d'une série d'après sa fiche : vue si tous ses
    /// épisodes connus le sont. Appelé au chargement de la fiche (le
    /// catalogue ne connaît pas les épisodes).
    pub fn update_series_watched(
        &mut self,
        series_id: i64,
        episode_ids: &[i64],
        now: i64,
    ) -> Result<bool> {
        let mut all = !episode_ids.is_empty();
        for &id in episode_ids {
            if !self.is_watched(PlaybackKind::Episode, id)? {
                all = false;
                break;
            }
        }
        if all {
            self.conn.execute(
                "INSERT OR IGNORE INTO watched (kind, id, series_id, watched_at)
                 VALUES ('series', ?1, NULL, ?2)",
                params![series_id, now],
            )?;
        } else {
            self.conn.execute(
                "DELETE FROM watched WHERE kind = 'series' AND id = ?1",
                params![series_id],
            )?;
        }
        Ok(all)
    }

    /// Position à laquelle reprendre, en millisecondes.
    pub fn progress(&self, kind: PlaybackKind, id: i64) -> Result<Option<i64>> {
        Ok(self
            .conn
            .query_row(
                "SELECT position_ms FROM resume WHERE kind = ?1 AND id = ?2",
                params![kind.key(), id],
                |r| r.get(0),
            )
            .optional()?)
    }

    /// Catégorie « Continuer » : une seule entrée par série (son dernier
    /// épisode), de la plus récente à la plus ancienne. `kind` garde seulement
    /// les films (`Movie`) ou les séries (`Episode`), avant la limite.
    pub fn continue_watching(
        &self,
        kind: Option<PlaybackKind>,
        limit: usize,
    ) -> Result<Vec<ResumeEntry>> {
        let mut stmt = self.conn.prepare_cached(&format!(
            // Une ligne par film, et une par série : la dernière écrite (rowid le
            // plus grand), même si deux épisodes ont été enregistrés dans la
            // même seconde.
            "SELECT kind, id, position_ms, duration_ms, updated_at, title, image, extension, series_id
             FROM (
                SELECT r.*, ROW_NUMBER() OVER (
                    PARTITION BY CASE WHEN r.series_id IS NULL THEN r.kind || ':' || r.id
                                      ELSE 'series:' || r.series_id END
                    ORDER BY r.updated_at DESC, r.rowid DESC
                ) AS rank
                FROM resume r
             ) r
             WHERE r.rank = 1 AND (?2 IS NULL OR r.kind = ?2){}
             ORDER BY updated_at DESC, id
             LIMIT ?1",
            self.resume_lock_filter()
        ))?;
        let rows = stmt.query_map(params![limit as i64, kind.map(PlaybackKind::key)], |r| {
            let kind: String = r.get(0)?;
            Ok((
                kind,
                ResumeEntry {
                    resume: Resume {
                        kind: PlaybackKind::Movie,
                        id: r.get(1)?,
                        position_ms: r.get(2)?,
                        duration_ms: r.get(3)?,
                        title: r.get(5)?,
                        image: r.get(6)?,
                        extension: r.get(7)?,
                        series_id: r.get(8)?,
                    },
                    updated_at: r.get(4)?,
                },
            ))
        })?;
        let mut entries = Vec::new();
        for row in rows {
            let (kind, mut entry) = row?;
            if let Some(kind) = PlaybackKind::from_key(&kind) {
                entry.resume.kind = kind;
                entries.push(entry);
            }
        }
        Ok(entries)
    }

    fn joined_items(
        &self,
        sql: &str,
        section: Section,
        offset: usize,
        limit: usize,
    ) -> Result<Vec<CatalogItem>> {
        let mut stmt = self.conn.prepare_cached(sql)?;
        let rows = stmt.query_map(params![section.key(), limit as i64, offset as i64], |r| {
            row_to_item(section, r)
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }
}

//! Données de l'utilisateur : favoris, récents et reprise de lecture.
//!
//! Elles vivent dans des tables à part, indexées par l'identifiant du
//! fournisseur : un rafraîchissement du catalogue ne les efface pas. Une
//! entrée qui disparaît du catalogue est simplement masquée, et réapparaît si
//! le fournisseur la remet.

use rusqlite::{OptionalExtension, Transaction, params};

use crate::catalog::{Catalog, CatalogItem, ITEM_COLUMNS, PROGRESS_JOIN, Section, row_to_item};
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
        ended INTEGER NOT NULL,
        PRIMARY KEY (kind, id)
    );
    CREATE INDEX watched_by_series ON watched (series_id, watched_at);
    CREATE TABLE series_episode (
        series_id INTEGER NOT NULL,
        episode_id INTEGER NOT NULL,
        PRIMARY KEY (series_id, episode_id)
    );
";

/// Ordre des épisodes d'une série (celui de la fiche), pour proposer l'épisode
/// suivant à la fin d'un épisode. `NULL` tant que la fiche n'a pas été
/// rechargée depuis cette migration : pas de proposition sans ordre connu.
pub(crate) const MIGRATION_8: &str = "
    ALTER TABLE series_episode ADD COLUMN sort_order INTEGER;
";

/// Nom du réglage qui retient la dernière catégorie d'une section.
fn last_category_setting(section: Section) -> String {
    format!("last_category_{}", section.key())
}

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
        // Les 2 dernières minutes ne comptent qu'au-delà de la moitié : un
        // contenu court n'est pas « vu » dès son lancement.
        self.duration_ms > 0
            && (self.position_ms as f64 >= self.duration_ms as f64 * FINISHED_RATIO
                || (self.duration_ms - self.position_ms <= FINISHED_REMAINING_MS
                    && self.position_ms * 2 >= self.duration_ms))
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
                 FROM favorite f JOIN item i ON i.section = f.section AND i.id = f.id{PROGRESS_JOIN}
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
    pub fn add_recent(&mut self, section: Section, id: i64, now: i64) -> Result<()> {
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
                 FROM recent r JOIN item i ON i.section = r.section AND i.id = r.id{PROGRESS_JOIN}
                 WHERE r.section = ?1{} ORDER BY r.watched_at DESC, r.id LIMIT ?2 OFFSET ?3",
                self.visibility("i", false)
            ),
            section,
            0,
            limit,
        )
    }

    // --- Dernière catégorie ------------------------------------------------

    /// Dernière catégorie ouverte d'une section, pour y revenir à l'ouverture.
    /// La clé est celle de l'interface (« category:12 », « @continue »…) : le
    /// cœur la garde telle quelle. Propre à l'abonnement, comme les favoris.
    pub fn last_category(&self, section: Section) -> Result<Option<String>> {
        self.setting(&last_category_setting(section))
    }

    /// Retient la catégorie ouverte d'une section ; `None` l'oublie.
    pub fn set_last_category(&mut self, section: Section, key: Option<&str>) -> Result<()> {
        let setting = last_category_setting(section);
        match key {
            Some(key) => self.set_setting(&setting, key),
            None => {
                self.conn
                    .execute("DELETE FROM setting WHERE key = ?1", params![setting])?;
                Ok(())
            }
        }
    }

    // --- Reprise de lecture -----------------------------------------------

    /// Enregistre la position de lecture. Moins de 30 s : rien n'est retenu.
    /// Contenu terminé : l'entrée est retirée de « Continuer ». Un épisode
    /// terminé propose le suivant (`offer_next_episode`).
    pub fn save_progress(&mut self, resume: &Resume, now: i64) -> Result<()> {
        if resume.is_finished() {
            // La fin de lecture et l'épisode suivant dans une seule transaction :
            // une proposition qui échoue ne laisse pas un épisode fini sans suite.
            let tx = self.conn.transaction()?;
            Self::mark_watched(&tx, resume.kind, resume.id, resume.series_id, true, now)?;
            if resume.kind == PlaybackKind::Episode
                && let Some(series_id) = resume.series_id
            {
                Self::offer_next_episode(&tx, series_id, resume, now)?;
            }
            tx.commit()?;
            return Ok(());
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
    /// Marqué à la main, il garde sa position (« Reprendre » sur la fiche) :
    /// masquée tant que la marque est la plus récente, de retour avec « non vu ».
    pub fn set_watched(
        &mut self,
        kind: PlaybackKind,
        id: i64,
        series_id: Option<i64>,
        watched: bool,
        now: i64,
    ) -> Result<()> {
        if watched {
            self.insert_watched(kind, id, series_id, false, now)
        } else {
            self.conn.execute(
                "DELETE FROM watched WHERE kind = ?1 AND id = ?2",
                params![kind.key(), id],
            )?;
            Ok(())
        }
    }

    /// `ended` : vu jusqu'au bout, la position n'a plus d'objet et est effacée ;
    /// à la main, elle est gardée. Pour une série, une reprise plus ancienne
    /// que le dernier épisode fini n'est plus « le dernier épisode regardé ».
    fn insert_watched(
        &mut self,
        kind: PlaybackKind,
        id: i64,
        series_id: Option<i64>,
        ended: bool,
        now: i64,
    ) -> Result<()> {
        let tx = self.conn.transaction()?;
        Self::mark_watched(&tx, kind, id, series_id, ended, now)?;
        tx.commit()?;
        Ok(())
    }

    /// Écrit la marque « vu » (et efface la position d'une fin de lecture)
    /// dans la transaction de l'appelant.
    fn mark_watched(
        tx: &Transaction<'_>,
        kind: PlaybackKind,
        id: i64,
        series_id: Option<i64>,
        ended: bool,
        now: i64,
    ) -> Result<()> {
        tx.execute(
            "INSERT OR REPLACE INTO watched (kind, id, series_id, watched_at, ended)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![kind.key(), id, series_id, now, ended],
        )?;
        if ended {
            tx.execute(
                "DELETE FROM resume WHERE kind = ?1 AND id = ?2",
                params![kind.key(), id],
            )?;
        }
        Ok(())
    }

    /// Après un épisode vu jusqu'au bout : le premier épisode suivant pas encore
    /// vu (ordre de la fiche, saison suivante comprise) est proposé dans
    /// « Continuer », par une reprise à 0. Rien si c'était le dernier, ni si
    /// l'ordre n'est pas connu. Une reprise déjà gardée pour cet épisode (position,
    /// titre, image, extension) reste telle quelle : seule sa date change.
    fn offer_next_episode(
        tx: &Transaction<'_>,
        series_id: i64,
        finished: &Resume,
        now: i64,
    ) -> Result<()> {
        let next: Option<i64> = tx
            .query_row(
                "SELECT e.episode_id FROM series_episode e
                 WHERE e.series_id = ?1
                   AND e.sort_order > (SELECT sort_order FROM series_episode
                                       WHERE series_id = ?1 AND episode_id = ?2)
                   AND NOT EXISTS (SELECT 1 FROM watched w
                                   WHERE w.kind = 'episode' AND w.id = e.episode_id)
                 ORDER BY e.sort_order LIMIT 1",
                params![series_id, finished.id],
                |r| r.get(0),
            )
            .optional()?;
        let Some(next) = next else { return Ok(()) };
        tx.execute(
            "INSERT INTO resume
                (kind, id, position_ms, duration_ms, updated_at, title, image, extension, series_id)
             VALUES ('episode', ?1, 0, 0, ?2, ?3, ?4, NULL, ?5)
             ON CONFLICT (kind, id) DO UPDATE SET updated_at = excluded.updated_at",
            params![next, now, finished.title, finished.image, series_id],
        )?;
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

    /// Retient la liste des épisodes d'une série, lue dans sa fiche, dans
    /// l'ordre de la lecture : une série est vue quand tous ses épisodes
    /// connus le sont (le catalogue ne connaît pas les épisodes). La marque
    /// suit ensuite chaque épisode vu ou non vu, sans recharger la fiche.
    /// L'ordre sert à proposer l'épisode suivant (`offer_next_episode`).
    pub fn remember_episodes(&mut self, series_id: i64, episode_ids: &[i64]) -> Result<()> {
        let tx = self.conn.transaction()?;
        tx.execute(
            "DELETE FROM series_episode WHERE series_id = ?1",
            params![series_id],
        )?;
        {
            let mut insert = tx.prepare_cached(
                "INSERT OR IGNORE INTO series_episode (series_id, episode_id, sort_order)
                 VALUES (?1, ?2, ?3)",
            )?;
            for (index, &id) in episode_ids.iter().enumerate() {
                insert.execute(params![series_id, id, index as i64])?;
            }
        }
        tx.commit()?;
        Ok(())
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
             WHERE r.rank = 1 AND (?2 IS NULL OR r.kind = ?2) AND {} AND {}{}
             ORDER BY updated_at DESC, id
             LIMIT ?1",
            not_superseded!(),
            not_marked_seen!("r"),
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

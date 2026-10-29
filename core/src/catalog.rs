//! Cache local du catalogue (SQLite).
//!
//! L'app s'ouvre toujours sur ce cache ; le réseau ne fait que le rafraîchir.
//! Les écrans lisent page par page : un catalogue de 30 000 films ne passe
//! jamais en entier en mémoire (appareil de référence : 2 Go).

use std::path::Path;

use rusqlite::{Connection, OptionalExtension, Transaction, TransactionBehavior, params};

use crate::account::{AccountKey, AccountMatch, account_match};
use crate::client::XtreamClient;
use crate::error::{Error, Result};
use crate::model::{Category, LiveStream, Movie, Series};
use crate::names::clean_name;

/// Section du catalogue.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Section {
    Live,
    Movies,
    Series,
}

impl Section {
    pub const ALL: [Section; 3] = [Section::Live, Section::Movies, Section::Series];

    pub(crate) fn key(self) -> &'static str {
        match self {
            Section::Live => "live",
            Section::Movies => "movies",
            Section::Series => "series",
        }
    }
}

/// Catégorie telle que stockée : nom nettoyé du tri et marquage adulte.
#[derive(Debug, Clone, PartialEq)]
pub struct CachedCategory {
    pub id: String,
    pub name: String,
    /// Adulte par son nom, ou parce que le fournisseur marque adultes toutes
    /// ses entrées.
    pub is_adult: bool,
    /// Adulte et code PIN pas saisi : l'ouvrir demande le code.
    pub locked: bool,
    /// Masquée par l'utilisateur dans les réglages.
    pub hidden: bool,
}

/// Entrée d'une liste (chaîne, film ou série), commune aux trois sections.
#[derive(Debug, Clone, PartialEq)]
pub struct CatalogItem {
    pub section: Section,
    pub id: i64,
    pub name: String,
    pub image: Option<String>,
    pub category_id: Option<String>,
    /// Extension du fichier (films) ; `None` pour le direct et les séries.
    pub extension: Option<String>,
    pub epg_channel_id: Option<String>,
    pub rating: Option<String>,
    pub plot: Option<String>,
    /// Numéro donné par le fournisseur (chaînes) ; `None` s'il n'en donne pas.
    pub number: Option<i64>,
    /// Lecture commencée : le film, ou le dernier épisode commencé d'une série.
    pub progress: Option<Progress>,
    /// Film vu, ou série dont tous les épisodes connus (dernière fiche
    /// chargée) sont vus.
    pub watched: bool,
}

/// Position d'une lecture commencée, pour la barre de progression.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Progress {
    pub position_ms: i64,
    pub duration_ms: i64,
}

/// Résultat d'un rafraîchissement.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefreshOutcome {
    /// Le cache a été remplacé par la réponse du fournisseur.
    Updated { categories: usize, items: usize },
    /// Liste vide (catégories ou entrées) alors que le cache en contenait : on
    /// garde le cache, les panneaux renvoient parfois une liste vide par erreur.
    KeptCache,
}

/// Âge maximal du cache avant rafraîchissement automatique : 24 h.
pub const MAX_AGE_SECS: i64 = 24 * 60 * 60;

const MIGRATIONS: &[&str] = &[
    // 1 : catalogue
    "CREATE TABLE category (
        section TEXT NOT NULL,
        id TEXT NOT NULL,
        name TEXT NOT NULL,
        sort_key TEXT NOT NULL,
        is_adult INTEGER NOT NULL,
        PRIMARY KEY (section, id)
    );
    CREATE TABLE item (
        section TEXT NOT NULL,
        id INTEGER NOT NULL,
        name TEXT NOT NULL,
        sort_key TEXT NOT NULL,
        search_key TEXT NOT NULL,
        sort_num INTEGER NOT NULL,
        image TEXT,
        category_id TEXT,
        extension TEXT,
        epg_channel_id TEXT,
        rating TEXT,
        plot TEXT,
        PRIMARY KEY (section, id)
    );
    CREATE INDEX item_by_category ON item (section, category_id, sort_num, sort_key, id);
    CREATE INDEX item_by_section ON item (section, sort_num, sort_key, id);
    CREATE TABLE refresh (
        section TEXT PRIMARY KEY,
        refreshed_at INTEGER NOT NULL
    );",
    // 2 : données de l'utilisateur, conservées d'un rafraîchissement à l'autre
    crate::user_data::MIGRATION,
    // 3 : contrôle parental, catégories masquées, EPG court
    crate::parental::MIGRATION,
    // 4 : progression des séries dans les listes, éléments vus
    crate::user_data::MIGRATION_4,
    // 5 : nom tel que le fournisseur l'envoie ; `name` devient le nom affiché
    // (`names::clean_name`), recalculé à l'ouverture pour les lignes déjà là.
    "ALTER TABLE item ADD COLUMN raw_name TEXT;",
];

/// Cache du catalogue d'un abonnement.
pub struct Catalog {
    pub(crate) conn: Connection,
    /// Catégories adultes déverrouillées par le code PIN, pour cette session
    /// seulement : chaque ouverture repart verrouillée.
    pub(crate) parental_unlocked: bool,
    /// Voir [`Catalog::restrict_sections_to`].
    pub(crate) sections_owner: Option<AccountKey>,
}

impl Catalog {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        Self::init(Connection::open(path)?)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(mut conn: Connection) -> Result<Self> {
        // Le même fichier peut être ouvert deux fois (lectures, et
        // enregistrement d'une section) : une écriture attend la fin de
        // l'autre au lieu d'échouer.
        conn.busy_timeout(std::time::Duration::from_secs(30))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        let tx = conn.transaction()?;
        for (index, sql) in MIGRATIONS.iter().enumerate().skip(version as usize) {
            tx.execute_batch(sql)?;
            tx.pragma_update(None, "user_version", index as i64 + 1)?;
        }
        clean_stored_names(&tx)?;
        tx.commit()?;
        Ok(Self {
            conn,
            parental_unlocked: false,
            sections_owner: None,
        })
    }

    /// Vrai si la section n'a jamais été chargée ou date de plus de 24 h.
    pub fn needs_refresh(&self, section: Section, now: i64) -> Result<bool> {
        Ok(match self.refreshed_at(section)? {
            Some(at) => now - at >= MAX_AGE_SECS || now < at,
            None => true,
        })
    }

    /// Dernière mise à jour du catalogue entier : la plus ancienne des
    /// sections chargées (le catalogue est à jour au moins à cette date).
    /// `None` si aucune section n'a encore été chargée.
    pub fn updated_at(&self) -> Result<Option<i64>> {
        Ok(self
            .conn
            .query_row("SELECT MIN(refreshed_at) FROM refresh", [], |r| r.get(0))?)
    }

    pub fn refreshed_at(&self, section: Section) -> Result<Option<i64>> {
        Ok(self
            .conn
            .query_row(
                "SELECT refreshed_at FROM refresh WHERE section = ?1",
                [section.key()],
                |r| r.get(0),
            )
            .optional()?)
    }

    /// Catégories visibles d'une section, triées par nom (l'ordre du
    /// fournisseur varie d'une requête à l'autre). Les catégories masquées
    /// sont exclues ; les catégories adultes restent listées, verrouillées.
    pub fn categories(&self, section: Section) -> Result<Vec<CachedCategory>> {
        Ok(self
            .all_categories(section)?
            .into_iter()
            .filter(|c| !c.hidden)
            .collect())
    }

    /// Toutes les catégories, masquées comprises (écran des réglages).
    pub fn all_categories(&self, section: Section) -> Result<Vec<CachedCategory>> {
        let mut stmt = self.conn.prepare_cached(
            "SELECT c.id, c.name, c.is_adult, h.id IS NOT NULL
             FROM category c
             LEFT JOIN hidden_category h ON h.section = c.section AND h.id = c.id
             WHERE c.section = ?1 ORDER BY c.sort_key, c.id",
        )?;
        let unlocked = self.parental_unlocked;
        let rows = stmt.query_map([section.key()], |r| {
            let is_adult: bool = r.get(2)?;
            Ok(CachedCategory {
                id: r.get(0)?,
                name: r.get(1)?,
                is_adult,
                locked: is_adult && !unlocked,
                hidden: r.get(3)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// Nombre d'entrées visibles d'une section, éventuellement limité à une
    /// catégorie.
    pub fn count(&self, section: Section, category_id: Option<&str>) -> Result<usize> {
        self.check_category(section, category_id)?;
        let n: i64 = match category_id {
            Some(id) => self.conn.query_row(
                &format!(
                    "SELECT COUNT(*) FROM item i WHERE i.section = ?1 AND i.category_id = ?2{}",
                    self.visibility("i", false)
                ),
                params![section.key(), id],
                |r| r.get(0),
            )?,
            None => self.conn.query_row(
                &format!(
                    "SELECT COUNT(*) FROM item i WHERE i.section = ?1{}",
                    self.visibility("i", true)
                ),
                [section.key()],
                |r| r.get(0),
            )?,
        };
        Ok(n as usize)
    }

    /// Une page d'entrées. Le direct suit la numérotation du fournisseur, les
    /// films et séries l'ordre alphabétique. Les deux requêtes lisent un index
    /// déjà trié : pas de tri de toute la section à chaque page. Ouvrir une
    /// catégorie verrouillée renvoie [`Error::ParentalLock`].
    pub fn items(
        &self,
        section: Section,
        category_id: Option<&str>,
        offset: usize,
        limit: usize,
    ) -> Result<Vec<CatalogItem>> {
        self.check_category(section, category_id)?;
        let (limit, offset) = (limit as i64, offset as i64);
        let rows = match category_id {
            Some(id) => {
                let mut stmt = self.conn.prepare_cached(&format!(
                    "SELECT {ITEM_COLUMNS} FROM item i{PROGRESS_JOIN}
                     WHERE i.section = ?1 AND i.category_id = ?2{}
                     ORDER BY i.sort_num, i.sort_key, i.id LIMIT ?3 OFFSET ?4",
                    self.visibility("i", false)
                ))?;
                stmt.query_map(params![section.key(), id, limit, offset], |r| {
                    row_to_item(section, r)
                })?
                .collect::<rusqlite::Result<_>>()?
            }
            None => {
                let mut stmt = self.conn.prepare_cached(&format!(
                    "SELECT {ITEM_COLUMNS} FROM item i{PROGRESS_JOIN}
                     WHERE i.section = ?1{}
                     ORDER BY i.sort_num, i.sort_key, i.id LIMIT ?2 OFFSET ?3",
                    self.visibility("i", true)
                ))?;
                stmt.query_map(params![section.key(), limit, offset], |r| {
                    row_to_item(section, r)
                })?
                .collect::<rusqlite::Result<_>>()?
            }
        };
        Ok(rows)
    }

    /// Une entrée, sauf si elle appartient à une catégorie verrouillée.
    pub fn item(&self, section: Section, id: i64) -> Result<Option<CatalogItem>> {
        let sql = format!(
            "SELECT {ITEM_COLUMNS} FROM item i{PROGRESS_JOIN} WHERE i.section = ?1 AND i.id = ?2{}",
            self.visibility("i", false),
        );
        Ok(self
            .conn
            .query_row(&sql, params![section.key(), id], |r| {
                row_to_item(section, r)
            })
            .optional()?)
    }

    /// Recherche par nom, insensible à la casse et aux accents. Chaque mot de
    /// la requête doit apparaître dans le nom. Les catégories masquées et
    /// verrouillées sont exclues.
    ///
    /// Les plus pertinents d'abord : la requête entière en début de mot
    /// (« retour vers » trouve « Retour vers le futur » avant « Le Retour du
    /// roi … version longue »), puis la requête entière ailleurs, puis le
    /// reste ; dans chaque groupe, l'ordre alphabétique.
    pub fn search(&self, section: Section, query: &str, limit: usize) -> Result<Vec<CatalogItem>> {
        let folded = fold(query);
        let phrase = folded.split_whitespace().collect::<Vec<_>>().join(" ");
        let words: Vec<String> = folded.split_whitespace().map(like_pattern).collect();
        if words.is_empty() {
            return Ok(Vec::new());
        }
        let mut sql =
            format!("SELECT {ITEM_COLUMNS} FROM item i{PROGRESS_JOIN} WHERE i.section = ?1");
        for i in 0..words.len() {
            sql.push_str(&format!(" AND i.search_key LIKE ?{} ESCAPE '\\'", i + 2));
        }
        sql.push_str(&self.visibility("i", true));
        // Début de mot : après une espace ou une décoration de fournisseur
        // (« FR:Retour », « |FR|Retour », « FR-Retour »).
        let p = words.len() + 2;
        let word_start = WORD_SEPARATORS
            .chars()
            .map(|c| format!("instr(' ' || i.search_key, '{c}' || ?{p}) > 0"))
            .collect::<Vec<_>>()
            .join(" OR ");
        sql.push_str(&format!(
            " ORDER BY CASE WHEN {word_start} THEN 0 \
               WHEN instr(i.search_key, ?{p}) > 0 THEN 1 ELSE 2 END, \
             i.sort_key, i.id LIMIT {limit}"
        ));
        let mut stmt = self.conn.prepare(&sql)?;
        let key = section.key();
        let mut values: Vec<&dyn rusqlite::ToSql> = vec![&key];
        for word in &words {
            values.push(word);
        }
        values.push(&phrase);
        let rows = stmt.query_map(values.as_slice(), |r| row_to_item(section, r))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    /// Remplace le direct par une réponse du fournisseur.
    pub fn store_live(
        &mut self,
        categories: &[Category],
        streams: &[LiveStream],
        now: i64,
    ) -> Result<RefreshOutcome> {
        self.store(Section::Live, categories, streams.len(), now, |tx| {
            let mut insert = tx.prepare(INSERT_ITEM)?;
            for s in streams {
                let raw = display_name(s.name.as_deref());
                let name = clean_name(&raw);
                insert.execute(params![
                    "live",
                    s.stream_id,
                    name,
                    sort_key(&name),
                    fold(&name),
                    s.num.unwrap_or(NO_NUM),
                    s.stream_icon,
                    s.category_id,
                    None::<String>,
                    s.epg_channel_id,
                    None::<String>,
                    None::<String>,
                    s.is_adult,
                    raw,
                ])?;
            }
            Ok(())
        })
    }

    /// Remplace les films par une réponse du fournisseur.
    pub fn store_movies(
        &mut self,
        categories: &[Category],
        movies: &[Movie],
        now: i64,
    ) -> Result<RefreshOutcome> {
        self.store(Section::Movies, categories, movies.len(), now, |tx| {
            let mut insert = tx.prepare(INSERT_ITEM)?;
            for m in movies {
                let raw = display_name(m.name.as_deref());
                let name = clean_name(&raw);
                insert.execute(params![
                    "movies",
                    m.stream_id,
                    name,
                    sort_key(&name),
                    fold(&name),
                    NO_NUM,
                    m.stream_icon,
                    m.category_id,
                    m.container_extension,
                    None::<String>,
                    m.rating,
                    None::<String>,
                    m.is_adult,
                    raw,
                ])?;
            }
            Ok(())
        })
    }

    /// Remplace les séries par une réponse du fournisseur.
    pub fn store_series(
        &mut self,
        categories: &[Category],
        series: &[Series],
        now: i64,
    ) -> Result<RefreshOutcome> {
        self.store(Section::Series, categories, series.len(), now, |tx| {
            let mut insert = tx.prepare(INSERT_ITEM)?;
            for s in series {
                let raw = display_name(s.name.as_deref());
                let name = clean_name(&raw);
                insert.execute(params![
                    "series",
                    s.series_id,
                    name,
                    sort_key(&name),
                    fold(&name),
                    NO_NUM,
                    s.cover,
                    s.category_id,
                    None::<String>,
                    None::<String>,
                    s.rating,
                    s.plot,
                    s.is_adult,
                    raw,
                ])?;
            }
            Ok(())
        })
    }

    /// Remplacement atomique d'une section : soit tout le nouveau catalogue,
    /// soit l'ancien intact.
    fn store(
        &mut self,
        section: Section,
        categories: &[Category],
        item_count: usize,
        now: i64,
        insert_items: impl FnOnce(&Transaction) -> rusqlite::Result<()>,
    ) -> Result<RefreshOutcome> {
        // Comptes bruts, sans masquage ni verrou parental : une section dont
        // tout est masqué n'est pas vide pour autant.
        let stored = |table: &str| -> Result<i64> {
            Ok(self.conn.query_row(
                &format!("SELECT COUNT(*) FROM {table} WHERE section = ?1"),
                [section.key()],
                |r| r.get(0),
            )?)
        };
        let lost_items = item_count == 0 && stored("item")? > 0;
        let lost_categories = categories.is_empty() && stored("category")? > 0;
        if lost_items || lost_categories {
            return Ok(RefreshOutcome::KeptCache);
        }
        let key = section.key();
        // Verrou d'écriture pris dès le début : le compte lié, relu ici, ne
        // change plus avant la fin de l'enregistrement.
        let tx = self
            .conn
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        if let Some(owner) = &self.sections_owner
            && account_match(&tx, owner)? != AccountMatch::Same
        {
            return Err(Error::AccountChanged);
        }
        tx.execute("DELETE FROM category WHERE section = ?1", [key])?;
        tx.execute("DELETE FROM item WHERE section = ?1", [key])?;
        {
            let mut insert = tx.prepare(
                "INSERT OR REPLACE INTO category (section, id, name, sort_key, is_adult)
                 VALUES (?1, ?2, ?3, ?4, ?5)",
            )?;
            for c in categories {
                let name = display_name(c.category_name.as_deref());
                insert.execute(params![
                    key,
                    c.category_id,
                    name,
                    sort_key(&name),
                    is_adult(&name)
                ])?;
            }
        }
        insert_items(&tx)?;
        // Catégorie dont le fournisseur marque toutes les entrées adultes :
        // adulte elle aussi (listée verrouillée, code demandé à l'ouverture),
        // comme une catégorie détectée par son nom. Recalculé à chaque
        // enregistrement, puisque les catégories viennent d'être réécrites.
        tx.execute(ADULT_BY_PROVIDER_FLAG, [key])?;
        // Entrées sans catégories, ou catégories sans entrées : réponse
        // probablement incomplète. On la garde, mais la section reste à
        // rafraîchir au prochain lancement. Les deux vides : section vide.
        if categories.is_empty() != (item_count == 0) {
            tx.execute("DELETE FROM refresh WHERE section = ?1", [key])?;
        } else {
            tx.execute(
                "INSERT OR REPLACE INTO refresh (section, refreshed_at) VALUES (?1, ?2)",
                params![key, now],
            )?;
        }
        tx.commit()?;
        Ok(RefreshOutcome::Updated {
            categories: categories.len(),
            items: item_count,
        })
    }
}

/// Contenu d'une section tel que reçu du fournisseur, pas encore enregistré.
#[derive(Debug, Clone)]
pub enum SectionData {
    Live(Vec<Category>, Vec<LiveStream>),
    Movies(Vec<Category>, Vec<Movie>),
    Series(Vec<Category>, Vec<Series>),
}

/// Télécharge une section chez le fournisseur, sans toucher au cache : le
/// cache reste lisible pendant les quelques secondes de téléchargement.
pub async fn fetch_section(client: &XtreamClient, section: Section) -> Result<SectionData> {
    Ok(match section {
        Section::Live => SectionData::Live(
            categories_with_retry(|| client.live_categories()).await?,
            client.live_streams(None).await?,
        ),
        Section::Movies => SectionData::Movies(
            categories_with_retry(|| client.movie_categories()).await?,
            client.movies(None).await?,
        ),
        Section::Series => SectionData::Series(
            categories_with_retry(|| client.series_categories()).await?,
            client.series(None).await?,
        ),
    })
}

impl Catalog {
    /// Enregistre une section téléchargée par [`fetch_section`].
    pub fn store_section(&mut self, data: &SectionData, now: i64) -> Result<RefreshOutcome> {
        match data {
            SectionData::Live(categories, items) => self.store_live(categories, items, now),
            SectionData::Movies(categories, items) => self.store_movies(categories, items, now),
            SectionData::Series(categories, items) => self.store_series(categories, items, now),
        }
    }
}

/// Télécharge une section chez le fournisseur et l'enregistre dans le cache.
/// En cas d'erreur réseau, le cache reste intact.
pub async fn refresh_section(
    client: &XtreamClient,
    catalog: &mut Catalog,
    section: Section,
    now: i64,
) -> Result<RefreshOutcome> {
    let data = fetch_section(client, section).await?;
    catalog.store_section(&data, now)
}

/// Tentatives pour une liste de catégories vide. Le panneau de référence
/// renvoie `[]` sur environ 60 % des appels `get_vod_categories` (mesuré le
/// 2026-10-28) ; une vraie liste vide coûte seulement ces quelques appels.
const CATEGORY_ATTEMPTS: u64 = 6;

async fn categories_with_retry<F, Fut>(fetch: F) -> Result<Vec<Category>>
where
    F: Fn() -> Fut,
    Fut: std::future::Future<Output = Result<Vec<Category>>>,
{
    let mut categories = fetch().await?;
    for attempt in 1..CATEGORY_ATTEMPTS {
        if !categories.is_empty() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(250 * attempt)).await;
        categories = fetch().await?;
    }
    Ok(categories)
}

/// Rang des entrées sans numéro : après toutes les chaînes numérotées.
const NO_NUM: i64 = i64::MAX;

/// Colonnes d'une entrée de liste. La progression vient de la reprise : le
/// film lui-même, ou l'épisode le plus récemment regardé de la série (rien
/// si un épisode plus récent a été vu jusqu'au bout). Une série est vue quand
/// tous ses épisodes connus (dernière fiche chargée) le sont.
pub(crate) const ITEM_COLUMNS: &str = "i.id, i.name, i.image, i.category_id, i.extension,
    i.epg_channel_id, i.rating, i.plot, i.sort_num, p.position_ms, p.duration_ms,
    CASE i.section
        WHEN 'movies' THEN EXISTS (SELECT 1 FROM watched w
                                   WHERE w.kind = 'movie' AND w.id = i.id)
        WHEN 'series' THEN EXISTS (SELECT 1 FROM series_episode e WHERE e.series_id = i.id)
            AND NOT EXISTS (SELECT 1 FROM series_episode e
                            WHERE e.series_id = i.id AND NOT EXISTS (
                                SELECT 1 FROM watched w
                                WHERE w.kind = 'episode' AND w.id = e.episode_id))
        ELSE 0
    END";

/// Jointure de la reprise d'une entrée `i` (colonnes `p.*` d'`ITEM_COLUMNS`),
/// à placer après `FROM … item i`.
pub(crate) const PROGRESS_JOIN: &str = concat!(
    " LEFT JOIN resume p ON p.rowid = CASE i.section
        WHEN 'movies' THEN (SELECT r.rowid FROM resume r
                            WHERE r.kind = 'movie' AND r.id = i.id)
        WHEN 'series' THEN (SELECT r.rowid FROM resume r
                            WHERE r.series_id = i.id AND r.kind = 'episode' AND ",
    not_superseded!(),
    "
                            ORDER BY r.updated_at DESC, r.rowid DESC LIMIT 1)
    END"
);

/// Caractères après lesquels un mot commence, pour le classement de la
/// recherche (sans apostrophe ni guillemet : ils iraient dans le SQL).
const WORD_SEPARATORS: &str = " |:-_.([/";

/// Marque adultes les catégories non vides dont toutes les entrées sont
/// marquées `is_adult` par le fournisseur. Une catégorie mixte garde le
/// filtrage entrée par entrée ([`Catalog::visibility`]). Si un rafraîchissement
/// y ajoute une entrée non marquée, elle devient mixte : elle s'ouvre sans
/// code, et ses entrées marquées restent cachées tant que le verrou est
/// fermé. Les deux sous-requêtes lisent l'index `item_by_category` et
/// s'arrêtent à la première entrée trouvée : une catégorie ordinaire coûte une
/// ou deux lectures, pas un parcours de ses entrées.
const ADULT_BY_PROVIDER_FLAG: &str = "UPDATE category SET is_adult = 1
    WHERE section = ?1 AND is_adult = 0
      AND EXISTS (SELECT 1 FROM item i
                  WHERE i.section = category.section AND i.category_id = category.id)
      AND NOT EXISTS (SELECT 1 FROM item i
                      WHERE i.section = category.section AND i.category_id = category.id
                        AND i.is_adult = 0)";

const INSERT_ITEM: &str = "INSERT OR REPLACE INTO item
    (section, id, name, sort_key, search_key, sort_num, image, category_id, extension,
     epg_channel_id, rating, plot, is_adult, raw_name)
    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)";

/// Lignes enregistrées avant le nom affiché (migration 5) : `name` brut est
/// gardé dans `raw_name`, puis remplacé par le nom affiché, avec ses clés de
/// tri et de recherche. Ne fait rien une fois toutes les lignes traitées.
fn clean_stored_names(tx: &rusqlite::Transaction<'_>) -> Result<()> {
    let rows: Vec<(String, i64, String)> = {
        let mut select = tx.prepare("SELECT section, id, name FROM item WHERE raw_name IS NULL")?;
        let rows = select.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
        rows.collect::<rusqlite::Result<_>>()?
    };
    let mut update = tx.prepare(
        "UPDATE item SET raw_name = name, name = ?3, sort_key = ?4, search_key = ?5
         WHERE section = ?1 AND id = ?2",
    )?;
    for (section, id, raw) in rows {
        let name = clean_name(&raw);
        update.execute(params![section, id, name, sort_key(&name), fold(&name)])?;
    }
    Ok(())
}

pub(crate) fn row_to_item(
    section: Section,
    r: &rusqlite::Row<'_>,
) -> rusqlite::Result<CatalogItem> {
    Ok(CatalogItem {
        section,
        id: r.get(0)?,
        name: r.get(1)?,
        image: r.get(2)?,
        category_id: r.get(3)?,
        extension: r.get(4)?,
        epg_channel_id: r.get(5)?,
        rating: r.get(6)?,
        plot: r.get(7)?,
        number: Some(r.get::<_, i64>(8)?).filter(|&n| n != NO_NUM),
        progress: match (r.get(9)?, r.get(10)?) {
            (Some(position_ms), Some(duration_ms)) => Some(Progress {
                position_ms,
                duration_ms,
            }),
            _ => None,
        },
        watched: r.get(11)?,
    })
}

fn display_name(name: Option<&str>) -> String {
    name.map(str::trim).unwrap_or_default().to_owned()
}

/// Minuscules sans accents, pour la recherche. Les ligatures s'écrivent en
/// deux lettres, comme on les tape : « Sœur » → « soeur ».
pub(crate) fn fold(text: &str) -> String {
    let mut folded = String::with_capacity(text.len());
    for c in text.chars().flat_map(char::to_lowercase) {
        match c {
            'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' => folded.push('a'),
            'ç' => folded.push('c'),
            'è' | 'é' | 'ê' | 'ë' => folded.push('e'),
            'ì' | 'í' | 'î' | 'ï' => folded.push('i'),
            'ñ' => folded.push('n'),
            'ò' | 'ó' | 'ô' | 'õ' | 'ö' => folded.push('o'),
            'ù' | 'ú' | 'û' | 'ü' => folded.push('u'),
            'ý' | 'ÿ' => folded.push('y'),
            'œ' => folded.push_str("oe"),
            'æ' => folded.push_str("ae"),
            other => folded.push(other),
        }
    }
    folded
}

/// Clé de tri : on ignore les décorations de tête (`|FR|`, `★`, `-`…) pour
/// que « |FR| Action » se range à « action ».
fn sort_key(name: &str) -> String {
    let folded = fold(name);
    let mut rest = folded.trim_start();
    loop {
        if let Some(after) = rest.strip_prefix('|').and_then(|r| r.split_once('|')) {
            rest = after.1.trim_start();
        } else if let Some(c) = rest.chars().next().filter(|c| !c.is_alphanumeric()) {
            rest = rest[c.len_utf8()..].trim_start();
        } else {
            break;
        }
    }
    if rest.is_empty() {
        folded
    } else {
        rest.to_owned()
    }
}

/// Catégorie adulte, verrouillée par défaut par le contrôle parental.
pub(crate) fn is_adult(name: &str) -> bool {
    let folded = fold(name);
    ["xxx", "adult", "adulte", "+18", "18+", "porn", "for adults"]
        .iter()
        .any(|marker| folded.contains(marker))
}

fn like_pattern(word: &str) -> String {
    let escaped = word
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_");
    format!("%{escaped}%")
}

impl From<rusqlite::Error> for Error {
    fn from(error: rusqlite::Error) -> Self {
        Error::Storage(error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sort_key_skips_decorations() {
        assert_eq!(sort_key("|FR| ✪ ULTRA ᵁᴴᴰ"), "ultra ᵁᴴᴰ".to_lowercase());
        assert_eq!(sort_key("  Été 2024"), "ete 2024");
        assert_eq!(sort_key("★★★"), "★★★");
    }

    #[test]
    fn fold_writes_ligatures_as_typed() {
        assert_eq!(fold("Sœurs"), "soeurs");
        assert_eq!(fold("ŒUVRE"), "oeuvre");
        assert_eq!(fold("Lætitia"), "laetitia");
        assert_eq!(fold("Éléphant Ça"), "elephant ca");
    }

    /// Les pages de liste lisent l'index dans l'ordre : ni parcours de toute
    /// la section ni tri temporaire (mesuré lent sur la Mi Box S).
    #[test]
    fn list_pages_use_an_index_without_sorting() {
        let catalog = Catalog::open_in_memory().unwrap();
        for filter in ["AND i.category_id = '1'", ""] {
            // Avec les filtres réels (verrou parental, catégories masquées).
            let sql = format!(
                "SELECT {ITEM_COLUMNS} FROM item i{PROGRESS_JOIN} WHERE i.section = 'movies' {filter}{}
                 ORDER BY i.sort_num, i.sort_key, i.id LIMIT 60 OFFSET 0",
                catalog.visibility("i", filter.is_empty())
            );
            let plan: Vec<String> = catalog
                .conn
                .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
                .unwrap()
                .query_map([], |r| r.get::<_, String>(3))
                .unwrap()
                .collect::<rusqlite::Result<_>>()
                .unwrap();
            let plan = plan.join(" | ");
            assert!(plan.contains("INDEX item_by_"), "{plan}");
            assert!(!plan.contains("TEMP B-TREE"), "{plan}");
        }
    }

    /// Le marquage des catégories entièrement adultes, à chaque
    /// enregistrement, lit l'index par catégorie : pas de parcours de toute
    /// la section.
    #[test]
    fn adult_by_provider_flag_uses_the_category_index() {
        let catalog = Catalog::open_in_memory().unwrap();
        let plan: Vec<String> = catalog
            .conn
            .prepare(&format!("EXPLAIN QUERY PLAN {ADULT_BY_PROVIDER_FLAG}"))
            .unwrap()
            .query_map(["movies"], |r| r.get::<_, String>(3))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        let on_items: Vec<&String> = plan.iter().filter(|step| step.contains(" i ")).collect();
        assert_eq!(on_items.len(), 2, "{plan:?}");
        assert!(
            on_items
                .iter()
                .all(|step| step.contains("INDEX item_by_category")),
            "{plan:?}"
        );
    }

    #[test]
    fn adult_detection() {
        assert!(is_adult("|XXX| Adultes"));
        assert!(is_adult("FR: ADULT 18+"));
        assert!(!is_adult("|FR| Sport"));
        assert!(!is_adult("Documentaires"));
    }
}

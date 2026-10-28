//! Contrôle parental et catégories masquées.
//!
//! Les catégories adultes (détectées par leur nom) sont verrouillées : elles
//! restent listées, mais leur contenu n'apparaît nulle part (listes,
//! recherche, favoris, récents, « Continuer ») tant que le code PIN n'a pas
//! été saisi. Le déverrouillage ne vaut que pour la session en cours.
//!
//! Les catégories masquées disparaissent de la navigation et de la recherche ;
//! les favoris et les récents qu'elles contiennent restent visibles, puisque
//! l'utilisateur les a choisis explicitement.

use rusqlite::{OptionalExtension, params};
use sha2::{Digest, Sha256};

use crate::catalog::{Catalog, Section};
use crate::error::{Error, Result};

pub(crate) const MIGRATION: &str = "
    CREATE TABLE setting (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    CREATE TABLE hidden_category (
        section TEXT NOT NULL,
        id TEXT NOT NULL,
        PRIMARY KEY (section, id)
    );
    CREATE TABLE epg (
        stream_id INTEGER NOT NULL,
        start INTEGER NOT NULL,
        stop INTEGER NOT NULL,
        title TEXT NOT NULL,
        description TEXT,
        PRIMARY KEY (stream_id, start)
    );
    CREATE TABLE epg_fetch (
        stream_id INTEGER PRIMARY KEY,
        fetched_at INTEGER NOT NULL
    );
";

const PIN_KEY: &str = "parental_pin";
/// Après 5 codes faux, nouvelle tentative possible au bout de 30 s.
const MAX_ATTEMPTS: u32 = 5;
const LOCKOUT_SECS: i64 = 30;

/// État des tentatives de code, en mémoire pour la session.
#[derive(Debug, Default)]
pub(crate) struct PinAttempts {
    failures: u32,
    blocked_until: i64,
}

impl Catalog {
    // --- Code PIN ----------------------------------------------------------

    pub fn has_pin(&self) -> Result<bool> {
        Ok(self.stored_pin()?.is_some())
    }

    /// Définit ou change le code PIN (4 chiffres). Un code existant doit être
    /// confirmé par `current`.
    pub fn set_pin(&mut self, new_pin: &str, current: Option<&str>) -> Result<()> {
        if !is_valid_pin(new_pin) {
            return Err(Error::InvalidPin);
        }
        if let Some(stored) = self.stored_pin()?
            && !current.is_some_and(|pin| verify(&stored, pin))
        {
            return Err(Error::WrongPin);
        }
        let mut salt = [0u8; 16];
        getrandom::fill(&mut salt).map_err(|e| Error::Storage(e.to_string()))?;
        let value = format!("{}:{}", hex(&salt), hex(&digest(&salt, new_pin)));
        self.conn.execute(
            "INSERT OR REPLACE INTO setting (key, value) VALUES (?1, ?2)",
            params![PIN_KEY, value],
        )?;
        Ok(())
    }

    /// Déverrouille les catégories adultes pour la session.
    pub fn unlock_parental(&mut self, pin: &str, now: i64) -> Result<()> {
        if self.pin_attempts.failures >= MAX_ATTEMPTS && now < self.pin_attempts.blocked_until {
            return Err(Error::TooManyAttempts {
                retry_in_secs: self.pin_attempts.blocked_until - now,
            });
        }
        let stored = self.stored_pin()?.ok_or(Error::WrongPin)?;
        if verify(&stored, pin) {
            self.pin_attempts = PinAttempts::default();
            self.parental_unlocked = true;
            return Ok(());
        }
        self.pin_attempts.failures += 1;
        if self.pin_attempts.failures >= MAX_ATTEMPTS {
            self.pin_attempts.blocked_until = now + LOCKOUT_SECS;
        }
        Err(Error::WrongPin)
    }

    /// Reverrouille les catégories adultes.
    pub fn lock_parental(&mut self) {
        self.parental_unlocked = false;
    }

    pub fn is_parental_unlocked(&self) -> bool {
        self.parental_unlocked
    }

    fn stored_pin(&self) -> Result<Option<String>> {
        Ok(self
            .conn
            .query_row("SELECT value FROM setting WHERE key = ?1", [PIN_KEY], |r| {
                r.get(0)
            })
            .optional()?)
    }

    // --- Catégories masquées ---------------------------------------------

    pub fn set_category_hidden(&mut self, section: Section, id: &str, hidden: bool) -> Result<()> {
        if hidden {
            self.conn.execute(
                "INSERT OR IGNORE INTO hidden_category (section, id) VALUES (?1, ?2)",
                params![section.key(), id],
            )?;
        } else {
            self.conn.execute(
                "DELETE FROM hidden_category WHERE section = ?1 AND id = ?2",
                params![section.key(), id],
            )?;
        }
        Ok(())
    }

    // --- Filtres SQL partagés --------------------------------------------

    /// Conditions de visibilité d'une entrée `alias` de la table `item`.
    /// `browse` ajoute l'exclusion des catégories masquées.
    pub(crate) fn visibility(&self, alias: &str, browse: bool) -> String {
        let mut sql = String::new();
        if !self.parental_unlocked {
            sql.push_str(&format!(
                " AND NOT EXISTS (SELECT 1 FROM category c WHERE c.section = {alias}.section
                  AND c.id = {alias}.category_id AND c.is_adult = 1)"
            ));
        }
        if browse {
            sql.push_str(&format!(
                " AND NOT EXISTS (SELECT 1 FROM hidden_category h WHERE h.section = {alias}.section
                  AND h.id = {alias}.category_id)"
            ));
        }
        sql
    }

    /// Exclut de « Continuer » les films et séries de catégories verrouillées.
    pub(crate) fn resume_lock_filter(&self) -> &'static str {
        if self.parental_unlocked {
            return "";
        }
        " AND NOT EXISTS (SELECT 1 FROM item i JOIN category c
            ON c.section = i.section AND c.id = i.category_id AND c.is_adult = 1
            WHERE (r.kind = 'movie' AND i.section = 'movies' AND i.id = r.id)
               OR (r.kind = 'episode' AND i.section = 'series' AND i.id = r.series_id))"
    }

    /// Refuse l'ouverture d'une catégorie verrouillée.
    pub(crate) fn check_category(&self, section: Section, category_id: Option<&str>) -> Result<()> {
        let Some(id) = category_id else { return Ok(()) };
        if self.parental_unlocked {
            return Ok(());
        }
        let adult: Option<bool> = self
            .conn
            .query_row(
                "SELECT is_adult FROM category WHERE section = ?1 AND id = ?2",
                params![section.key(), id],
                |r| r.get(0),
            )
            .optional()?;
        if adult == Some(true) {
            return Err(Error::ParentalLock);
        }
        Ok(())
    }
}

fn is_valid_pin(pin: &str) -> bool {
    pin.len() == 4 && pin.bytes().all(|b| b.is_ascii_digit())
}

fn digest(salt: &[u8], pin: &str) -> Vec<u8> {
    let mut hasher = Sha256::new();
    hasher.update(salt);
    hasher.update(pin.as_bytes());
    hasher.finalize().to_vec()
}

fn verify(stored: &str, pin: &str) -> bool {
    let Some((salt, expected)) = stored.split_once(':') else {
        return false;
    };
    let Some(salt) = unhex(salt) else {
        return false;
    };
    hex(&digest(&salt, pin)) == expected
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn unhex(text: &str) -> Option<Vec<u8>> {
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(text.get(i..i + 2)?, 16).ok())
        .collect()
}

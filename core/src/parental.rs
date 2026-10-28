//! Contrôle parental et catégories masquées.
//!
//! Les catégories adultes (détectées par leur nom) et les flux que le
//! fournisseur marque `is_adult` sont verrouillés. Les catégories restent
//! listées, mais leur contenu n'apparaît nulle part (listes,
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
    ALTER TABLE item ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0;
";

const PIN_KEY: &str = "parental_pin";
const FAILURES_KEY: &str = "parental_pin_failures";
const BLOCKED_UNTIL_KEY: &str = "parental_pin_blocked_until";
/// Après 5 codes faux, nouvelle tentative possible au bout de 30 s. Le
/// compteur est enregistré : relancer l'app ne le remet pas à zéro.
const MAX_ATTEMPTS: i64 = 5;
const LOCKOUT_SECS: i64 = 30;

impl Catalog {
    // --- Code PIN ----------------------------------------------------------

    pub fn has_pin(&self) -> Result<bool> {
        Ok(self.stored_pin()?.is_some())
    }

    /// Définit ou change le code PIN (4 chiffres). Un code existant doit être
    /// confirmé par `current`, avec la même limite d'essais que le
    /// déverrouillage.
    pub fn set_pin(&mut self, new_pin: &str, current: Option<&str>, now: i64) -> Result<()> {
        if !is_valid_pin(new_pin) {
            return Err(Error::InvalidPin);
        }
        if self.has_pin()? {
            self.check_pin(current.ok_or(Error::WrongPin)?, now)?;
        }
        let mut salt = [0u8; 16];
        getrandom::fill(&mut salt).map_err(|e| Error::Storage(e.to_string()))?;
        let value = format!("{}:{}", hex(&salt), hex(&digest(&salt, new_pin)));
        self.set_setting(PIN_KEY, &value)?;
        Ok(())
    }

    /// Déverrouille les catégories adultes pour la session.
    pub fn unlock_parental(&mut self, pin: &str, now: i64) -> Result<()> {
        self.check_pin(pin, now)?;
        self.parental_unlocked = true;
        Ok(())
    }

    /// Vérifie un code en comptant les échecs : au 5e code faux, plus aucun
    /// essai (même juste) pendant 30 s.
    fn check_pin(&mut self, pin: &str, now: i64) -> Result<()> {
        let mut failures = self.setting_i64(FAILURES_KEY)?.unwrap_or(0);
        let blocked_until = self.setting_i64(BLOCKED_UNTIL_KEY)?.unwrap_or(0);
        if failures >= MAX_ATTEMPTS {
            // Une horloge reculée ne prolonge pas le blocage au-delà de 30 s.
            if now < blocked_until && blocked_until - now <= LOCKOUT_SECS {
                return Err(Error::TooManyAttempts {
                    retry_in_secs: blocked_until - now,
                });
            }
            // Blocage écoulé : de nouveau 5 essais.
            failures = 0;
        }
        let stored = self.stored_pin()?.ok_or(Error::WrongPin)?;
        if verify(&stored, pin) {
            self.set_setting(FAILURES_KEY, "0")?;
            return Ok(());
        }
        let failures = failures + 1;
        self.set_setting(FAILURES_KEY, &failures.to_string())?;
        if failures >= MAX_ATTEMPTS {
            self.set_setting(BLOCKED_UNTIL_KEY, &(now + LOCKOUT_SECS).to_string())?;
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
        self.setting(PIN_KEY)
    }

    pub(crate) fn setting(&self, key: &str) -> Result<Option<String>> {
        Ok(self
            .conn
            .query_row("SELECT value FROM setting WHERE key = ?1", [key], |r| {
                r.get(0)
            })
            .optional()?)
    }

    fn setting_i64(&self, key: &str) -> Result<Option<i64>> {
        Ok(self.setting(key)?.and_then(|v| v.parse().ok()))
    }

    pub(crate) fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO setting (key, value) VALUES (?1, ?2)",
            params![key, value],
        )?;
        Ok(())
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
            // Verrou fermé par défaut : une entrée n'est visible que si elle
            // n'est pas marquée adulte par le fournisseur et si sa catégorie
            // est connue et non adulte. Une catégorie absente du cache (liste
            // de catégories vide, ou omise par le panneau) ne laisse rien
            // passer.
            sql.push_str(&format!(
                " AND {alias}.is_adult = 0
                  AND ({alias}.category_id IS NULL OR EXISTS (SELECT 1 FROM category c
                       WHERE c.section = {alias}.section AND c.id = {alias}.category_id
                       AND c.is_adult = 0))"
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

    /// Filtre de « Continuer » (ligne de reprise `r`) : verrou fermé, une
    /// entrée n'apparaît que si son film ou sa série est au catalogue et
    /// visible selon les mêmes règles que les listes.
    pub(crate) fn resume_lock_filter(&self) -> String {
        if self.parental_unlocked {
            return String::new();
        }
        format!(
            " AND EXISTS (SELECT 1 FROM item i
                WHERE i.section = CASE r.kind WHEN 'movie' THEN 'movies' ELSE 'series' END
                  AND i.id = CASE r.kind WHEN 'movie' THEN r.id ELSE r.series_id END{})",
            self.visibility("i", false)
        )
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

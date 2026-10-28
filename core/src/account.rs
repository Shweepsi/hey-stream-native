//! Lien entre le cache local et l'abonnement.
//!
//! Le cache est propre à un identifiant : se connecter avec un autre
//! identifiant efface catalogue, favoris, reprise, code PIN et EPG de
//! l'ancien, au lieu de les mélanger (identifiants de flux d'un fournisseur
//! servis avec l'URL d'un autre). Le même identifiant sur une autre adresse
//! (un fournisseur en sert souvent plusieurs pour le même compte) garde
//! favoris, reprise, code PIN et catégories masquées : seul le catalogue est
//! rechargé.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::catalog::Catalog;
use crate::client::XtreamClient;
use crate::error::{Error, Result};
use crate::model::AccountInfo;
use crate::parental::hex;
use crate::url::LiveFormat;

const USER_KEY: &str = "account_user";
const SERVER_KEY: &str = "account_server";
const INFO_KEY: &str = "account_info";

/// Infos de la dernière authentification réussie, gardées pour l'accueil
/// hors ligne (date d'expiration) et le choix du format du direct.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CachedAccount {
    /// Date d'expiration (timestamp Unix), absente pour un compte illimité.
    pub expires_at: Option<i64>,
    pub max_connections: Option<i64>,
    pub active_connections: Option<i64>,
    pub allowed_output_formats: Vec<String>,
    /// Date de la vérification (timestamp Unix).
    pub checked_at: i64,
}

/// Tables vidées à la déconnexion ou au changement d'identifiant.
const TABLES: &[&str] = &[
    "category",
    "item",
    "refresh",
    "favorite",
    "recent",
    "resume",
    "watched",
    "setting",
    "hidden_category",
    "epg",
    "epg_fetch",
];

/// Tables vidées quand seule l'adresse du serveur change.
const SERVER_TABLES: &[&str] = &["refresh", "epg", "epg_fetch"];

/// Empreintes de l'identifiant et de l'adresse du serveur, sans le mot de
/// passe : changer de mot de passe ne vide pas le cache. Rien de lisible
/// n'est stocké.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountKey {
    user: String,
    server: String,
}

/// Le cache comparé à un abonnement.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AccountMatch {
    /// Cache lié à aucun abonnement : neuf, ou restes d'une déconnexion.
    Unbound,
    /// Même identifiant, même adresse.
    Same,
    /// Même identifiant, autre adresse.
    OtherServer,
    /// Autre identifiant.
    OtherUser,
}

impl XtreamClient {
    pub fn account_key(&self) -> AccountKey {
        AccountKey {
            user: digest(self.username()),
            server: digest(self.base_url().as_str()),
        }
    }
}

fn digest(text: &str) -> String {
    hex(&Sha256::digest(text.as_bytes()))
}

impl Catalog {
    /// Compare le cache à un abonnement, sans rien modifier.
    pub fn account_match(&self, key: &AccountKey) -> Result<AccountMatch> {
        let Some(user) = self.setting(USER_KEY)? else {
            return Ok(AccountMatch::Unbound);
        };
        Ok(if user != key.user {
            AccountMatch::OtherUser
        } else if self.setting(SERVER_KEY)?.as_deref() == Some(key.server.as_str()) {
            AccountMatch::Same
        } else {
            AccountMatch::OtherServer
        })
    }

    /// Associe le cache à un abonnement et renvoie ce qu'il était avant :
    /// autre identifiant ou cache sans abonnement, tout est effacé ; même
    /// identifiant sur une autre adresse, le catalogue est à recharger et
    /// l'EPG effacé, le reste gardé.
    pub fn bind_account(&mut self, key: &AccountKey) -> Result<AccountMatch> {
        let found = self.account_match(key)?;
        let everything = match found {
            AccountMatch::Same => return Ok(found),
            AccountMatch::OtherServer => false,
            AccountMatch::Unbound | AccountMatch::OtherUser => true,
        };
        let tables = if everything { TABLES } else { SERVER_TABLES };
        let tx = self.conn.transaction()?;
        for table in tables {
            tx.execute(&format!("DELETE FROM {table}"), [])?;
        }
        for (name, value) in [(USER_KEY, &key.user), (SERVER_KEY, &key.server)] {
            tx.execute(
                "INSERT OR REPLACE INTO setting (key, value) VALUES (?1, ?2)",
                [name, value.as_str()],
            )?;
        }
        tx.commit()?;
        if everything {
            self.parental_unlocked = false;
        }
        Ok(found)
    }

    /// Retient les infos d'une authentification réussie.
    pub fn save_account(&mut self, info: &AccountInfo, now: i64) -> Result<CachedAccount> {
        let cached = CachedAccount {
            expires_at: info.expires_at,
            max_connections: info.max_connections,
            active_connections: info.active_connections,
            allowed_output_formats: info.allowed_output_formats.clone(),
            checked_at: now,
        };
        let json = serde_json::to_string(&cached).map_err(|e| Error::Storage(e.to_string()))?;
        self.set_setting(INFO_KEY, &json)?;
        Ok(cached)
    }

    /// Infos de la dernière authentification réussie, sans requête réseau ;
    /// `None` si l'abonnement n'a jamais été vérifié sur cet appareil.
    pub fn cached_account(&self) -> Result<Option<CachedAccount>> {
        Ok(self
            .setting(INFO_KEY)?
            .and_then(|json| serde_json::from_str(&json).ok()))
    }

    /// Format du direct à demander, d'après les formats autorisés connus.
    pub fn live_format(&self, preferred: LiveFormat) -> Result<LiveFormat> {
        let allowed = self
            .cached_account()?
            .map(|a| a.allowed_output_formats)
            .unwrap_or_default();
        Ok(LiveFormat::choose(preferred, &allowed))
    }

    /// Efface toutes les données de l'abonnement (déconnexion), y compris le
    /// lien au compte, et reverrouille le contrôle parental.
    pub fn clear_all(&mut self) -> Result<()> {
        let tx = self.conn.transaction()?;
        for table in TABLES {
            tx.execute(&format!("DELETE FROM {table}"), [])?;
        }
        tx.commit()?;
        self.parental_unlocked = false;
        Ok(())
    }
}

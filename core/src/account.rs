//! Lien entre le cache local et l'abonnement.
//!
//! Le cache est propre à un abonnement : se connecter à un autre serveur ou
//! un autre compte efface catalogue, favoris, reprise, code PIN et EPG de
//! l'ancien, au lieu de les mélanger (identifiants de flux d'un fournisseur
//! servis avec l'URL d'un autre).

use sha2::{Digest, Sha256};

use crate::catalog::Catalog;
use crate::client::XtreamClient;
use crate::error::Result;

const ACCOUNT_KEY: &str = "account";

/// Tables vidées à la déconnexion ou au changement d'abonnement.
const TABLES: &[&str] = &[
    "category",
    "item",
    "refresh",
    "favorite",
    "recent",
    "resume",
    "setting",
    "hidden_category",
    "epg",
    "epg_fetch",
];

impl XtreamClient {
    /// Empreinte de l'abonnement (serveur + identifiant), sans le mot de
    /// passe : changer de mot de passe ne vide pas le cache. Rien de lisible
    /// n'est stocké.
    pub fn account_key(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(self.base_url().as_str().as_bytes());
        hasher.update(b"\n");
        hasher.update(self.username().as_bytes());
        hasher
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    }
}

impl Catalog {
    /// Associe le cache à un abonnement. S'il appartenait à un autre, tout est
    /// effacé d'abord ; renvoie vrai dans ce cas.
    pub fn bind_account(&mut self, key: &str) -> Result<bool> {
        let previous = self.setting(ACCOUNT_KEY)?;
        let switched = previous.as_deref().is_some_and(|p| p != key);
        if switched {
            self.clear_all()?;
        }
        if previous.as_deref() != Some(key) {
            self.set_setting(ACCOUNT_KEY, key)?;
        }
        Ok(switched)
    }

    /// Efface toutes les données de l'abonnement (déconnexion) et reverrouille
    /// le contrôle parental.
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

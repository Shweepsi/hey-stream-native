/// Erreurs du cœur. Elles portent un *type*, pas un texte affiché : chaque
/// interface traduit le type dans la langue de l'utilisateur.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("URL du serveur invalide")]
    InvalidServerUrl,
    #[error("identifiants manquants")]
    MissingCredentials,
    #[error("identifiants refusés par le fournisseur")]
    Unauthorized,
    #[error("abonnement expiré ou désactivé")]
    AccountInactive,
    #[error("API Xtream introuvable à cette adresse")]
    NotFound,
    #[error("erreur du serveur (HTTP {0})")]
    Server(u16),
    #[error("réponse inattendue (HTTP {0})")]
    UnexpectedStatus(u16),
    #[error("réponse illisible")]
    InvalidResponse,
    #[error("erreur réseau")]
    Network(#[source] reqwest::Error),
}

pub type Result<T> = std::result::Result<T, Error>;

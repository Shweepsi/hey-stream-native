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
    #[error("catégorie verrouillée par le contrôle parental")]
    ParentalLock,
    #[error("le code doit comporter 4 chiffres")]
    InvalidPin,
    #[error("code incorrect")]
    WrongPin,
    #[error("trop de codes incorrects, réessayez dans {retry_in_secs} s")]
    TooManyAttempts { retry_in_secs: i64 },
    #[error("erreur du cache local : {0}")]
    Storage(String),
    /// Le cache n'est plus lié à l'abonnement de cette connexion (voir
    /// `Catalog::restrict_sections_to`) : rien n'y est enregistré.
    #[error("cache qui n'est plus lié à cet abonnement")]
    AccountChanged,
    #[error("erreur réseau")]
    Network(#[source] reqwest::Error),
}

impl Error {
    /// Erreur réseau sans l'URL de la requête : elle contient l'identifiant
    /// et le mot de passe, et `reqwest::Error` l'affiche dans son `Debug`.
    pub(crate) fn network(error: reqwest::Error) -> Self {
        Error::Network(error.without_url())
    }
}

pub type Result<T> = std::result::Result<T, Error>;

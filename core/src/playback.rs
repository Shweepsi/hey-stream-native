//! Que faire quand la lecture d'un flux échoue : relancer, ou s'arrêter et
//! dire pourquoi.
//!
//! Le cœur ne lit pas la vidéo et ne produit aucun texte : il décide, selon le
//! code HTTP de la réponse du fournisseur (quand il y en a un) et le nombre de
//! relances déjà faites. L'interface lit le code dans l'erreur du lecteur,
//! attend le délai demandé, ou affiche le message de la raison d'arrêt.

/// Relances permises avant d'abandonner (après une coupure ou une erreur
/// qu'un nouvel essai peut guérir).
pub const MAX_RETRIES: u32 = 6;

/// Attente la plus longue entre deux essais, en secondes.
const MAX_DELAY_SECS: u32 = 8;

/// Pourquoi la lecture s'arrête sans relancer (ou ne se relance plus).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopReason {
    /// 509 : le fournisseur refuse une connexion de plus (abonnement utilisé
    /// ailleurs). Relancer ne sert à rien tant que l'autre flux tourne.
    SubscriptionInUse,
    /// 401 ou 403 : compte expiré, suspendu ou refusé.
    AccountRefused,
    /// 404 encore après un essai : le flux n'existe pas chez le fournisseur.
    NotFound,
    /// Toutes les relances ont échoué (coupure réseau, serveur en panne).
    Failed,
}

/// Décision après un échec de lecture.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetryAction {
    /// Rouvrir le flux après `delay_secs` secondes.
    Retry { delay_secs: u32 },
    /// Abandonner : l'interface affiche la raison et propose « Réessayer ».
    Stop(StopReason),
}

/// Décide de la suite après un échec de lecture.
///
/// - `status` : code HTTP de la réponse du fournisseur, `None` sans réponse
///   (coupure réseau, flux terminé, erreur du décodeur…) ;
/// - `attempt` : relances déjà faites pour ce flux (0 au premier échec).
///
/// Règle :
/// - 509 → arrêt « abonnement utilisé ailleurs » ;
/// - 401, 403 → arrêt « compte expiré ou refusé » ;
/// - 404 → une seule relance, puis arrêt « flux introuvable » ;
/// - le reste (autres codes, pas de code) → relance après 1, 2, 4, 8, 8 puis
///   8 s, [`MAX_RETRIES`] fois, puis arrêt.
pub fn playback_retry(status: Option<u16>, attempt: u32) -> RetryAction {
    match status {
        Some(509) => return RetryAction::Stop(StopReason::SubscriptionInUse),
        Some(401 | 403) => return RetryAction::Stop(StopReason::AccountRefused),
        Some(404) if attempt >= 1 => return RetryAction::Stop(StopReason::NotFound),
        _ => {}
    }
    if attempt >= MAX_RETRIES {
        // Relances épuisées.
        return RetryAction::Stop(StopReason::Failed);
    }
    RetryAction::Retry {
        delay_secs: (1u32 << attempt.min(3)).min(MAX_DELAY_SECS),
    }
}

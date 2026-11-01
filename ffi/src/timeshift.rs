//! Différé sur le direct (`docs/DECISIONS.md`, « Différé sur le direct »).
//!
//! [`Timeshift`] est le tampon d'une chaîne : la copie (bloquante,
//! [`HeyStreamCore::run_timeshift`](crate::HeyStreamCore::run_timeshift))
//! tient l'unique connexion ; le lecteur lit les fichiers du tampon
//! ([`Timeshift::locate`], [`Timeshift::segment`]) ; un enregistrement de la
//! même chaîne le suit ([`Timeshift::attach`], puis
//! [`HeyStreamCore::record_following`](crate::HeyStreamCore::record_following)).

use std::sync::{Arc, Mutex};

use xtream_core as xc;

/// Comment la copie du différé s'est arrêtée.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TuneEnd {
    /// Plus personne ne regarde ni n'enregistre, ou arrêtée par l'app.
    Stopped,
    /// La première connexion n'a rien donné : lecture réseau à la place,
    /// qui affiche l'erreur habituelle.
    OpenFailed,
    /// Réserve du disque atteinte.
    OutOfSpace,
    /// Disque débranché ou fichier refusé.
    WriteFailed,
    ConnectionLost,
}

/// Coupure du flux dans le tampon (secondes Unix).
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct TimeshiftGap {
    pub from: f64,
    pub to: f64,
}

/// Partie disponible du tampon (barre de temps).
#[derive(Debug, Clone, uniffi::Record)]
pub struct TimeshiftWindow {
    pub oldest: f64,
    pub newest: f64,
    pub bytes: u64,
    pub gaps: Vec<TimeshiftGap>,
    pub receiving: bool,
}

/// Où lire pour un moment du tampon.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TimeshiftSpot {
    pub seq: u64,
    pub run: u64,
    pub path: String,
    pub offset: u64,
    pub at: f64,
}

/// Un fichier du tampon.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TimeshiftSegment {
    pub seq: u64,
    pub run: u64,
    pub path: String,
    pub start: f64,
    pub end: f64,
    pub bytes: u64,
    pub complete: bool,
}

/// Débit mesuré par le tampon.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct TimeshiftRate {
    pub bytes: u64,
    pub secs: f64,
}

#[derive(uniffi::Object)]
pub struct Timeshift {
    pub(crate) inner: xc::Tuner,
}

#[uniffi::export]
impl Timeshift {
    pub fn stream_id(&self) -> i64 {
        self.inner.stream_id()
    }

    /// Aperçu ou plein écran (vrai), ou plus personne (faux) : sans
    /// spectateur ni enregistrement, la copie s'arrête.
    pub fn set_viewing(&self, viewing: bool) {
        self.inner.set_viewing(viewing);
    }

    /// Arrête la copie (connexion reprise pour une autre chaîne) ; un
    /// enregistrement qui la suivait finit incomplet.
    pub fn stop(&self) {
        self.inner.stop();
    }

    pub fn end(&self) -> Option<TuneEnd> {
        self.inner.end().map(Into::into)
    }

    pub fn window(&self) -> Option<TimeshiftWindow> {
        self.inner.window().map(|w| TimeshiftWindow {
            oldest: w.oldest,
            newest: w.newest,
            bytes: w.bytes,
            gaps: w
                .gaps
                .into_iter()
                .map(|g| TimeshiftGap {
                    from: g.from,
                    to: g.to,
                })
                .collect(),
            receiving: w.receiving,
        })
    }

    pub fn locate(&self, at: f64) -> Option<TimeshiftSpot> {
        self.inner.locate(at).map(|s| TimeshiftSpot {
            seq: s.seq,
            run: s.run,
            path: s.path.to_string_lossy().into_owned(),
            offset: s.offset,
            at: s.at,
        })
    }

    pub fn segment(&self, seq: u64) -> Option<TimeshiftSegment> {
        self.inner.segment(seq).map(|s| TimeshiftSegment {
            seq: s.seq,
            run: s.run,
            path: s.path.to_string_lossy().into_owned(),
            start: s.start,
            end: s.end,
            bytes: s.bytes,
            complete: s.complete,
        })
    }

    pub fn time_at(&self, seq: u64, offset: u64) -> Option<f64> {
        self.inner.time_at(seq, offset)
    }

    /// Heure du tampon (monotone), à comparer à `window`.
    pub fn clock(&self) -> f64 {
        self.inner.clock()
    }

    /// Place prise : libre pour les enregistrements (le tampon la cède).
    pub fn bytes(&self) -> u64 {
        self.inner.bytes()
    }

    pub fn rate(&self) -> Option<TimeshiftRate> {
        self.inner
            .rate()
            .map(|(bytes, secs)| TimeshiftRate { bytes, secs })
    }

    /// Inscrit un enregistrement qui suivra le tampon depuis `from`. `None`
    /// si la copie est finie : l'enregistrement copie le flux lui-même.
    pub fn attach(&self, from: i64) -> Option<Arc<TimeshiftFollower>> {
        self.inner.attach(from).map(|follower| {
            Arc::new(TimeshiftFollower {
                start: follower.start(),
                inner: Mutex::new(Some(follower)),
            })
        })
    }
}

/// Enregistrement inscrit sur un tampon : rien de ce qu'il doit copier
/// n'est effacé tant qu'il n'est pas copié ou relâché.
#[derive(uniffi::Object)]
pub struct TimeshiftFollower {
    start: i64,
    pub(crate) inner: Mutex<Option<xc::Follower>>,
}

#[uniffi::export]
impl TimeshiftFollower {
    /// Premier moment copié : à passer à `RecordingStore::begin_from`.
    pub fn start(&self) -> i64 {
        self.start
    }

    /// Désinscrit sans copier (démarrage refusé).
    pub fn release(&self) {
        self.take();
    }
}

impl TimeshiftFollower {
    pub(crate) fn take(&self) -> Option<xc::Follower> {
        self.inner
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
    }
}

/// Efface les tampons laissés par un arrêt brutal (au lancement, sans
/// différé en cours), dans le dossier des enregistrements.
#[uniffi::export]
pub fn purge_timeshift(dir: String) {
    xc::purge_timeshift(std::path::Path::new(&dir));
}

impl From<xc::TuneEnd> for TuneEnd {
    fn from(e: xc::TuneEnd) -> Self {
        match e {
            xc::TuneEnd::Stopped => Self::Stopped,
            xc::TuneEnd::OpenFailed => Self::OpenFailed,
            xc::TuneEnd::OutOfSpace => Self::OutOfSpace,
            xc::TuneEnd::WriteFailed => Self::WriteFailed,
            xc::TuneEnd::ConnectionLost => Self::ConnectionLost,
        }
    }
}

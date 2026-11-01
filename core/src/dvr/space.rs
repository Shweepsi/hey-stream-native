//! Espace disque : réserve système, estimation et place libre d'un volume.

use std::path::Path;

/// Estimation de départ d'une chaîne jamais mesurée : 3 Go par heure.
pub const DEFAULT_BYTES_PER_HOUR: u64 = 3_000_000_000;
/// Réserve minimale toujours laissée libre : 1 Go…
const RESERVE_MIN_BYTES: u64 = 1_000_000_000;
/// … ou 10 % du disque, le plus grand des deux.
const RESERVE_RATIO: u64 = 10;

/// Taille et place libre d'un volume.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VolumeSpace {
    pub total_bytes: u64,
    /// Place utilisable par l'app.
    pub free_bytes: u64,
}

impl VolumeSpace {
    /// Place toujours laissée libre : 1 Go ou 10 % du disque.
    pub fn reserve_bytes(&self) -> u64 {
        RESERVE_MIN_BYTES.max(self.total_bytes / RESERVE_RATIO)
    }

    /// Place que les enregistrements peuvent prendre, réserve déduite.
    pub fn usable_bytes(&self) -> u64 {
        self.free_bytes.saturating_sub(self.reserve_bytes())
    }
}

/// Taille et place libre du volume qui contient `dir`. `None` si le dossier
/// n'existe pas (volume débranché) ou si le système ne répond pas.
#[cfg(unix)]
pub fn volume_space(dir: &Path) -> Option<VolumeSpace> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    let path = CString::new(dir.as_os_str().as_bytes()).ok()?;
    let mut stat = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    // SAFETY : `path` est une chaîne C valide et `stat` une zone de la bonne
    // taille, remplie par `statvfs` quand il renvoie 0.
    let stat = unsafe {
        if libc::statvfs(path.as_ptr(), stat.as_mut_ptr()) != 0 {
            return None;
        }
        stat.assume_init()
    };
    let block = stat.f_frsize as u64;
    Some(VolumeSpace {
        total_bytes: (stat.f_blocks as u64).saturating_mul(block),
        free_bytes: (stat.f_bavail as u64).saturating_mul(block),
    })
}

#[cfg(not(unix))]
pub fn volume_space(_dir: &Path) -> Option<VolumeSpace> {
    None
}

/// Octets estimés pour `secs` secondes à `bytes_per_sec`.
pub(crate) fn estimate(bytes_per_sec: f64, secs: i64) -> u64 {
    (bytes_per_sec * secs.max(0) as f64).round() as u64
}

pub(crate) fn default_bytes_per_sec() -> f64 {
    DEFAULT_BYTES_PER_HOUR as f64 / 3600.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reserve_is_one_gigabyte_or_ten_percent() {
        let small = VolumeSpace {
            total_bytes: 8_000_000_000,
            free_bytes: 4_000_000_000,
        };
        assert_eq!(small.reserve_bytes(), 1_000_000_000);
        assert_eq!(small.usable_bytes(), 3_000_000_000);
        let large = VolumeSpace {
            total_bytes: 64_000_000_000,
            free_bytes: 5_000_000_000,
        };
        assert_eq!(large.reserve_bytes(), 6_400_000_000);
        assert_eq!(large.usable_bytes(), 0);
    }

    #[test]
    fn default_estimate_is_three_gigabytes_per_hour() {
        assert_eq!(estimate(default_bytes_per_sec(), 3600), 3_000_000_000);
        assert_eq!(estimate(default_bytes_per_sec(), -5), 0);
    }

    #[test]
    fn space_of_an_existing_and_a_missing_folder() {
        let space = volume_space(&std::env::temp_dir()).expect("dossier temporaire");
        assert!(space.total_bytes >= space.free_bytes);
        assert!(volume_space(Path::new("/dossier/qui/n/existe/pas")).is_none());
    }
}

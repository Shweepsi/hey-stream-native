//! Planning : fenêtres réelles des enregistrements, sans base ni horloge.
//!
//! Chaque enregistrement demande une fenêtre (programmes et marges). Sur
//! deux chaînes, des marges qui se chevauchent sont raccourcies : la limite
//! tombe au milieu de l'intervalle entre la fin du premier programme et le
//! début du second, jamais dans un programme. Sur une même chaîne, les
//! enregistrements qui se touchent sont fusionnés avant d'arriver ici
//! (voir `Recordings::schedule`), sauf si un programme d'une autre chaîne
//! se trouve entre eux : la même règle s'applique alors.

/// Enregistrement vu par le planning.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Planned {
    pub id: i64,
    pub stream_id: i64,
    /// Début du premier programme et fin du dernier.
    pub programme_start: i64,
    pub programme_stop: i64,
    /// Fenêtre demandée, marges comprises.
    pub start: i64,
    pub stop: i64,
}

/// Fenêtre réelle d'un enregistrement, après raccourcissement des marges.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Slot {
    pub id: i64,
    pub start: i64,
    pub stop: i64,
}

/// Vrai si les programmes eux-mêmes se chevauchent : seul cas refusé.
pub(crate) fn programmes_overlap(a: &Planned, b: &Planned) -> bool {
    a.programme_start < b.programme_stop && b.programme_start < a.programme_stop
}

/// Vrai si deux fenêtres demandées se chevauchent ou se touchent : sur la
/// même chaîne, elles forment un seul enregistrement.
pub(crate) fn windows_touch(a: &Planned, b: &Planned) -> bool {
    a.start <= b.stop && b.start <= a.stop
}

/// Fenêtres réelles de tous les enregistrements. Les programmes ne se
/// chevauchent pas (refusé à la programmation) ; les fenêtres rendues non
/// plus, et chacune contient toujours ses programmes.
pub(crate) fn slots(planned: &[Planned]) -> Vec<Slot> {
    let mut sorted: Vec<&Planned> = planned.iter().collect();
    sorted.sort_by_key(|p| (p.programme_start, p.id));
    sorted
        .iter()
        .enumerate()
        .map(|(i, p)| {
            let mut slot = Slot {
                id: p.id,
                start: p.start,
                stop: p.stop,
            };
            for (j, other) in sorted.iter().enumerate() {
                if j == i {
                    continue;
                }
                if j < i && other.stop > p.start {
                    slot.start = slot.start.max(boundary(other, p));
                } else if j > i && p.stop > other.start {
                    slot.stop = slot.stop.min(boundary(p, other));
                }
            }
            slot
        })
        .collect()
}

/// Limite entre deux enregistrements dont les marges se chevauchent :
/// milieu de l'intervalle entre les deux programmes, dans les deux fenêtres.
fn boundary(first: &Planned, second: &Planned) -> i64 {
    let low = first.programme_stop;
    let high = second.programme_start.max(low);
    (low + (high - low) / 2).clamp(second.start, first.stop.max(second.start))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn planned(id: i64, stream_id: i64, programme: (i64, i64), margins: (i64, i64)) -> Planned {
        Planned {
            id,
            stream_id,
            programme_start: programme.0,
            programme_stop: programme.1,
            start: programme.0 - margins.0,
            stop: programme.1 + margins.1,
        }
    }

    fn slot(slots: &[Slot], id: i64) -> (i64, i64) {
        let s = slots.iter().find(|s| s.id == id).unwrap();
        (s.start, s.stop)
    }

    #[test]
    fn distant_recordings_keep_their_margins() {
        let all = [
            planned(1, 10, (1000, 2000), (120, 300)),
            planned(2, 20, (5000, 6000), (120, 300)),
        ];
        let slots = slots(&all);
        assert_eq!(slot(&slots, 1), (880, 2300));
        assert_eq!(slot(&slots, 2), (4880, 6300));
    }

    #[test]
    fn back_to_back_on_two_channels_split_at_the_programme_change() {
        let all = [
            planned(1, 10, (1000, 2000), (120, 300)),
            planned(2, 20, (2000, 3000), (120, 300)),
        ];
        let slots = slots(&all);
        assert_eq!(slot(&slots, 1), (880, 2000));
        assert_eq!(slot(&slots, 2), (2000, 3300));
    }

    #[test]
    fn overlapping_margins_split_in_the_middle_of_the_gap() {
        // Fin à 2000, début à 2100 : marges 300 et 120 qui se chevauchent.
        let all = [
            planned(2, 20, (2100, 3000), (120, 300)),
            planned(1, 10, (1000, 2000), (120, 300)),
        ];
        let slots = slots(&all);
        assert_eq!(slot(&slots, 1), (880, 2050));
        assert_eq!(slot(&slots, 2), (2050, 3300));
    }

    #[test]
    fn a_short_programme_between_two_others_keeps_everything_apart() {
        let all = [
            planned(1, 10, (1000, 2000), (0, 1800)),
            planned(2, 20, (2000, 2300), (60, 60)),
            planned(3, 30, (2300, 4000), (1800, 0)),
        ];
        let slots = slots(&all);
        let (a, b, c) = (slot(&slots, 1), slot(&slots, 2), slot(&slots, 3));
        assert!(a.1 <= b.0 && b.1 <= c.0, "{a:?} {b:?} {c:?}");
        assert!(a.1 >= 2000 && b.0 <= 2000 && b.1 >= 2300 && c.0 <= 2300);
    }

    #[test]
    fn only_real_programme_overlaps_count() {
        let a = planned(1, 10, (1000, 2000), (120, 300));
        assert!(!programmes_overlap(
            &a,
            &planned(2, 20, (2000, 3000), (120, 300))
        ));
        assert!(programmes_overlap(
            &a,
            &planned(2, 20, (1999, 3000), (0, 0))
        ));
        assert!(windows_touch(&a, &planned(2, 10, (2400, 3000), (100, 0))));
        assert!(!windows_touch(&a, &planned(2, 10, (2500, 3000), (100, 0))));
    }
}

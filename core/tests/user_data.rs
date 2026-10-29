use serde_json::{from_value, json};
use xtream_core::{
    Catalog, LiveStream, Movie, PlaybackKind, RECENTS_LIMIT, Resume, Section, Series,
};

fn catalog_with_content() -> Catalog {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let live: Vec<LiveStream> = from_value(json!([
        {"stream_id": 1, "name": "TF1"},
        {"stream_id": 2, "name": "France 2"},
        {"stream_id": 3, "name": "M6"}
    ]))
    .unwrap();
    catalog.store_live(&[], &live, 0).unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 10, "name": "Amélie"},
        {"stream_id": 11, "name": "Intouchables"}
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 0).unwrap();
    // Séries au catalogue : « Continuer » n'affiche, verrou parental fermé,
    // que des contenus connus.
    let series: Vec<Series> = from_value(json!([
        {"series_id": 50, "name": "Lupin"},
        {"series_id": 70, "name": "Dix pour cent"}
    ]))
    .unwrap();
    catalog.store_series(&[], &series, 0).unwrap();
    catalog
}

fn resume(kind: PlaybackKind, id: i64, position_ms: i64, series_id: Option<i64>) -> Resume {
    Resume {
        kind,
        id,
        position_ms,
        duration_ms: 6_000_000,
        title: format!("Contenu {id}"),
        image: None,
        extension: Some("mkv".into()),
        series_id,
    }
}

#[test]
fn favorites_toggle_and_survive_refresh() {
    let mut catalog = catalog_with_content();
    assert!(catalog.toggle_favorite(Section::Live, 2, 100).unwrap());
    assert!(catalog.toggle_favorite(Section::Live, 3, 200).unwrap());
    assert!(catalog.is_favorite(Section::Live, 2).unwrap());
    assert!(!catalog.is_favorite(Section::Movies, 2).unwrap());

    let names = |c: &Catalog| -> Vec<String> {
        c.favorites(Section::Live, 0, 10)
            .unwrap()
            .into_iter()
            .map(|i| i.name)
            .collect()
    };
    assert_eq!(names(&catalog), ["M6", "France 2"]);

    // Rafraîchissement sans M6 : le favori est masqué, pas perdu.
    let without_m6: Vec<LiveStream> =
        from_value(json!([{"stream_id": 1, "name": "TF1"}, {"stream_id": 2, "name": "France 2"}]))
            .unwrap();
    catalog.store_live(&[], &without_m6, 1_000).unwrap();
    assert_eq!(names(&catalog), ["France 2"]);
    assert!(catalog.is_favorite(Section::Live, 3).unwrap());

    assert!(!catalog.toggle_favorite(Section::Live, 2, 300).unwrap());
    assert!(names(&catalog).is_empty());
}

#[test]
fn recents_are_ordered_deduplicated_and_capped() {
    let mut catalog = catalog_with_content();
    catalog.add_recent(Section::Live, 1, 10).unwrap();
    catalog.add_recent(Section::Live, 2, 20).unwrap();
    catalog.add_recent(Section::Live, 1, 30).unwrap();
    let ids: Vec<_> = catalog
        .recents(Section::Live, 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(ids, [1, 2]);

    for id in 0..(RECENTS_LIMIT as i64 + 10) {
        catalog
            .add_recent(Section::Movies, 1_000 + id, 100 + id)
            .unwrap();
    }
    catalog.add_recent(Section::Movies, 10, 10_000).unwrap();
    let recent_movies = catalog.recents(Section::Movies, 100).unwrap();
    // Seul le film 10 existe dans le catalogue ; les autres sont masqués.
    assert_eq!(recent_movies.len(), 1);
    assert_eq!(recent_movies[0].name, "Amélie");
}

#[test]
fn progress_thresholds() {
    let mut catalog = catalog_with_content();
    // Moins de 30 s : rien.
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 10, 20_000, None), 1)
        .unwrap();
    assert_eq!(catalog.progress(PlaybackKind::Movie, 10).unwrap(), None);

    catalog
        .save_progress(&resume(PlaybackKind::Movie, 10, 1_200_000, None), 2)
        .unwrap();
    assert_eq!(
        catalog.progress(PlaybackKind::Movie, 10).unwrap(),
        Some(1_200_000)
    );

    // À 1 min 30 de la fin : terminé, retiré de « Continuer ».
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 10, 5_910_000, None), 3)
        .unwrap();
    assert_eq!(catalog.progress(PlaybackKind::Movie, 10).unwrap(), None);
}

#[test]
fn continue_watching_keeps_last_episode_per_series() {
    let mut catalog = catalog_with_content();
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 600_000, None), 10)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 501, 600_000, Some(50)), 20)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 502, 900_000, Some(50)), 30)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 701, 900_000, Some(70)), 5)
        .unwrap();

    let row: Vec<_> = catalog
        .continue_watching(None, 10)
        .unwrap()
        .into_iter()
        .map(|e| (e.resume.kind, e.resume.id))
        .collect();
    assert_eq!(
        row,
        [
            (PlaybackKind::Episode, 502),
            (PlaybackKind::Movie, 11),
            (PlaybackKind::Episode, 701)
        ]
    );
    assert_eq!(catalog.continue_watching(None, 1).unwrap().len(), 1);
    // Deux épisodes de la même série enregistrés dans la même seconde : une
    // seule entrée, la dernière écrite.
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 503, 300_000, Some(50)), 30)
        .unwrap();
    let series_50: Vec<_> = catalog
        .continue_watching(None, 10)
        .unwrap()
        .into_iter()
        .filter(|e| e.resume.series_id == Some(50))
        .map(|e| e.resume.id)
        .collect();
    assert_eq!(series_50, [503]);

    // L'épisode précédent garde sa position s'il est relancé.
    assert_eq!(
        catalog.progress(PlaybackKind::Episode, 501).unwrap(),
        Some(600_000)
    );
}

#[test]
fn upgrade_from_catalog_only_database_keeps_catalog() {
    let dir = std::env::temp_dir().join(format!("hs-upgrade-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    {
        let mut catalog = Catalog::open(&path).unwrap();
        let live: Vec<LiveStream> = from_value(json!([{"stream_id": 1, "name": "TF1"}])).unwrap();
        catalog.store_live(&[], &live, 0).unwrap();
    }
    // Simule une base créée par la version précédente (migration 1 seule).
    {
        let conn = rusqlite::Connection::open(&path).unwrap();
        conn.execute_batch(
            "DROP TABLE favorite; DROP TABLE recent; DROP TABLE resume;
             DROP TABLE setting; DROP TABLE hidden_category; DROP TABLE epg; DROP TABLE epg_fetch;
             DROP TABLE watched; DROP TABLE series_episode;
             ALTER TABLE item DROP COLUMN is_adult;
             ALTER TABLE item DROP COLUMN raw_name;
             PRAGMA user_version = 1;",
        )
        .unwrap();
    }
    let mut catalog = Catalog::open(&path).unwrap();
    assert_eq!(catalog.count(Section::Live, None).unwrap(), 1);
    assert!(catalog.toggle_favorite(Section::Live, 1, 0).unwrap());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn continue_watching_filters_by_kind_before_the_limit() {
    let mut catalog = catalog_with_content();
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 600_000, None), 10)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 501, 600_000, Some(50)), 20)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 502, 600_000, Some(50)), 30)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 701, 600_000, Some(70)), 40)
        .unwrap();

    let ids = |kind| -> Vec<i64> {
        catalog
            .continue_watching(Some(kind), 1)
            .unwrap()
            .into_iter()
            .map(|e| e.resume.id)
            .collect()
    };
    // Les épisodes sont plus récents : le film reste trouvé avec une limite de 1.
    assert_eq!(ids(PlaybackKind::Movie), [11]);
    assert_eq!(ids(PlaybackKind::Episode), [701]);
    let series: Vec<_> = catalog
        .continue_watching(Some(PlaybackKind::Episode), 10)
        .unwrap()
        .into_iter()
        .map(|e| e.resume.id)
        .collect();
    assert_eq!(series, [701, 502]);
}

#[test]
fn lists_carry_number_and_progress() {
    let mut catalog = catalog_with_content();
    let live: Vec<LiveStream> = from_value(json!([
        {"stream_id": 1, "name": "TF1", "num": 1},
        {"stream_id": 2, "name": "Sans numéro"}
    ]))
    .unwrap();
    catalog.store_live(&[], &live, 0).unwrap();
    let numbers: Vec<_> = catalog
        .items(Section::Live, None, 0, 10)
        .unwrap()
        .into_iter()
        .map(|i| (i.id, i.number))
        .collect();
    assert_eq!(numbers, [(1, Some(1)), (2, None)]);

    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 600_000, None), 10)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 501, 900_000, Some(50)), 20)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 502, 300_000, Some(50)), 30)
        .unwrap();

    let progress = |catalog: &Catalog, section| -> Vec<(i64, Option<i64>)> {
        catalog
            .items(section, None, 0, 10)
            .unwrap()
            .into_iter()
            .map(|i| (i.id, i.progress.map(|p| p.position_ms)))
            .collect()
    };
    assert_eq!(
        progress(&catalog, Section::Movies),
        [(10, None), (11, Some(600_000))]
    );
    // Série : le dernier épisode regardé, pas le plus avancé.
    assert_eq!(
        progress(&catalog, Section::Series),
        [(70, None), (50, Some(300_000))]
    );
    let movie = catalog.item(Section::Movies, 11).unwrap().unwrap();
    assert_eq!(movie.progress.unwrap().duration_ms, 6_000_000);
    assert!(
        catalog.items(Section::Live, None, 0, 10).unwrap()[0]
            .progress
            .is_none()
    );

    // Terminé : la barre disparaît.
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 5_990_000, None), 40)
        .unwrap();
    assert_eq!(
        progress(&catalog, Section::Movies),
        [(10, None), (11, None)]
    );
}

#[test]
fn watched_marks_survive_rewatching_and_can_be_undone() {
    let mut catalog = catalog_with_content();
    let watched = |c: &Catalog, section| -> Vec<(i64, bool)> {
        c.items(section, None, 0, 10)
            .unwrap()
            .into_iter()
            .map(|i| (i.id, i.watched))
            .collect()
    };

    // Fin de lecture : vu, et retiré de « Continuer ».
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 600_000, None), 10)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 5_990_000, None), 20)
        .unwrap();
    assert!(catalog.is_watched(PlaybackKind::Movie, 11).unwrap());
    assert!(catalog.continue_watching(None, 10).unwrap().is_empty());
    assert_eq!(
        watched(&catalog, Section::Movies),
        [(10, false), (11, true)]
    );

    // Revu : la reprise repart, la marque reste.
    catalog
        .save_progress(&resume(PlaybackKind::Movie, 11, 600_000, None), 30)
        .unwrap();
    assert!(catalog.is_watched(PlaybackKind::Movie, 11).unwrap());
    assert_eq!(catalog.continue_watching(None, 10).unwrap().len(), 1);

    // À la main : vu retire de « Continuer », non vu retire la marque.
    catalog
        .set_watched(PlaybackKind::Movie, 11, None, true, 40)
        .unwrap();
    assert!(catalog.continue_watching(None, 10).unwrap().is_empty());
    catalog
        .set_watched(PlaybackKind::Movie, 11, None, false, 50)
        .unwrap();
    assert_eq!(
        watched(&catalog, Section::Movies),
        [(10, false), (11, false)]
    );

    // Série : vue quand tous ses épisodes connus le sont, mise à jour à
    // chaque épisode vu ou non vu, sans recharger la fiche.
    let series = |c: &Catalog| watched(c, Section::Series);
    catalog
        .set_watched(PlaybackKind::Episode, 501, Some(50), true, 60)
        .unwrap();
    assert_eq!(series(&catalog), [(70, false), (50, false)]); // fiche jamais chargée
    catalog.remember_episodes(50, &[501, 502]).unwrap();
    assert_eq!(series(&catalog), [(70, false), (50, false)]);
    catalog
        .set_watched(PlaybackKind::Episode, 502, Some(50), true, 80)
        .unwrap();
    assert_eq!(series(&catalog), [(70, false), (50, true)]);
    catalog
        .set_watched(PlaybackKind::Episode, 502, Some(50), false, 85)
        .unwrap();
    assert_eq!(series(&catalog), [(70, false), (50, false)]);
    catalog
        .set_watched(PlaybackKind::Episode, 502, Some(50), true, 90)
        .unwrap();
    // Nouvel épisode publié : la série n'est plus vue.
    catalog.remember_episodes(50, &[501, 502, 503]).unwrap();
    assert_eq!(series(&catalog), [(70, false), (50, false)]);
    // Fiche sans épisode : jamais « vue ».
    catalog.remember_episodes(70, &[]).unwrap();
    assert_eq!(series(&catalog), [(70, false), (50, false)]);
}

#[test]
fn short_content_is_not_watched_at_start() {
    let mut catalog = catalog_with_content();
    let clip = |position_ms| Resume {
        duration_ms: 90_000,
        ..resume(PlaybackKind::Movie, 11, position_ms, None)
    };
    catalog.save_progress(&clip(0), 10).unwrap();
    catalog.save_progress(&clip(20_000), 20).unwrap();
    assert!(!catalog.is_watched(PlaybackKind::Movie, 11).unwrap());
    // Au-delà de la moitié, à moins de 2 min de la fin : vu.
    catalog.save_progress(&clip(50_000), 30).unwrap();
    assert!(catalog.is_watched(PlaybackKind::Movie, 11).unwrap());
}

#[test]
fn finished_episode_supersedes_older_series_progress() {
    let mut catalog = catalog_with_content();
    // E1 abandonné à 10 %, puis E2 vu jusqu'au bout.
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 501, 600_000, Some(50)), 10)
        .unwrap();
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 502, 5_990_000, Some(50)), 20)
        .unwrap();
    let series_progress = |c: &Catalog| {
        c.item(Section::Series, 50)
            .unwrap()
            .unwrap()
            .progress
            .map(|p| p.position_ms)
    };
    assert_eq!(series_progress(&catalog), None);
    assert!(catalog.continue_watching(None, 10).unwrap().is_empty());

    // Marquer à la main un vieil épisode ne cache pas la reprise en cours.
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 503, 900_000, Some(50)), 30)
        .unwrap();
    catalog
        .set_watched(PlaybackKind::Episode, 501, Some(50), true, 40)
        .unwrap();
    assert_eq!(series_progress(&catalog), Some(900_000));
    assert_eq!(
        catalog.continue_watching(None, 10).unwrap()[0].resume.id,
        503
    );
}

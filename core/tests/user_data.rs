use serde_json::{from_value, json};
use xtream_core::{Catalog, LiveStream, Movie, PlaybackKind, RECENTS_LIMIT, Resume, Section};

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
    catalog.mark_watched(Section::Live, 1, 10).unwrap();
    catalog.mark_watched(Section::Live, 2, 20).unwrap();
    catalog.mark_watched(Section::Live, 1, 30).unwrap();
    let ids: Vec<_> = catalog
        .recents(Section::Live, 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(ids, [1, 2]);

    for id in 0..(RECENTS_LIMIT as i64 + 10) {
        catalog
            .mark_watched(Section::Movies, 1_000 + id, 100 + id)
            .unwrap();
    }
    catalog.mark_watched(Section::Movies, 10, 10_000).unwrap();
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
        .continue_watching(10)
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
    assert_eq!(catalog.continue_watching(1).unwrap().len(), 1);
    // Deux épisodes de la même série enregistrés dans la même seconde : une
    // seule entrée, la dernière écrite.
    catalog
        .save_progress(&resume(PlaybackKind::Episode, 503, 300_000, Some(50)), 30)
        .unwrap();
    let series_50: Vec<_> = catalog
        .continue_watching(10)
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
            "DROP TABLE favorite; DROP TABLE recent; DROP TABLE resume; PRAGMA user_version = 1;",
        )
        .unwrap();
    }
    let mut catalog = Catalog::open(&path).unwrap();
    assert_eq!(catalog.count(Section::Live, None).unwrap(), 1);
    assert!(catalog.toggle_favorite(Section::Live, 1, 0).unwrap());
    std::fs::remove_dir_all(&dir).unwrap();
}

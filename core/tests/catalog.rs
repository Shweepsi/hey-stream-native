use serde_json::{from_value, json};
use xtream_core::{Catalog, Category, LiveStream, MAX_AGE_SECS, Movie, RefreshOutcome, Section};

fn categories(v: serde_json::Value) -> Vec<Category> {
    from_value(v).unwrap()
}

fn sample_live() -> (Vec<Category>, Vec<LiveStream>) {
    let cats = categories(json!([
        {"category_id": "2", "category_name": "|FR| Sport"},
        {"category_id": "1", "category_name": "|FR| Généralistes"},
        {"category_id": "9", "category_name": "XXX Adultes"}
    ]));
    let streams = from_value(json!([
        {"stream_id": 3, "name": "France 2", "num": 2, "category_id": "1"},
        {"stream_id": 1, "name": "TF1", "num": 1, "category_id": "1"},
        {"stream_id": 7, "name": "Équipe 21", "num": 3, "category_id": "2"}
    ]))
    .unwrap();
    (cats, streams)
}

#[test]
fn stores_and_reads_live_sorted() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let (cats, streams) = sample_live();
    let outcome = catalog.store_live(&cats, &streams, 1_000).unwrap();
    assert_eq!(
        outcome,
        RefreshOutcome::Updated {
            categories: 3,
            items: 3
        }
    );

    let names: Vec<_> = catalog
        .categories(Section::Live)
        .unwrap()
        .into_iter()
        .map(|c| (c.name, c.is_adult))
        .collect();
    assert_eq!(
        names,
        vec![
            ("|FR| Généralistes".to_owned(), false),
            ("|FR| Sport".to_owned(), false),
            ("XXX Adultes".to_owned(), true),
        ]
    );

    let page: Vec<_> = catalog
        .items(Section::Live, Some("1"), 0, 10)
        .unwrap()
        .into_iter()
        .map(|i| i.name)
        .collect();
    assert_eq!(page, ["TF1", "France 2"]);
    assert_eq!(catalog.count(Section::Live, None).unwrap(), 3);
    assert_eq!(
        catalog.items(Section::Live, None, 1, 1).unwrap()[0].name,
        "France 2"
    );
}

#[test]
fn empty_response_keeps_known_catalog() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let (cats, streams) = sample_live();
    catalog.store_live(&cats, &streams, 1_000).unwrap();

    let outcome = catalog.store_live(&[], &[], 2_000).unwrap();
    assert_eq!(outcome, RefreshOutcome::KeptCache);
    assert_eq!(catalog.count(Section::Live, None).unwrap(), 3);
    assert_eq!(catalog.refreshed_at(Section::Live).unwrap(), Some(1_000));

    // Vu sur le panneau de référence : 0 catégorie mais toutes les entrées.
    let outcome = catalog.store_live(&[], &streams, 2_000).unwrap();
    assert_eq!(outcome, RefreshOutcome::KeptCache);
    assert_eq!(catalog.categories(Section::Live).unwrap().len(), 3);
}

#[test]
fn refresh_replaces_section_without_touching_others() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let (cats, streams) = sample_live();
    catalog.store_live(&cats, &streams, 1_000).unwrap();
    let movies: Vec<Movie> =
        from_value(json!([{"stream_id": 5, "name": "Amélie", "container_extension": "mkv"}]))
            .unwrap();
    catalog.store_movies(&[], &movies, 1_000).unwrap();

    let fewer: Vec<LiveStream> = from_value(json!([{"stream_id": 1, "name": "TF1"}])).unwrap();
    catalog.store_live(&cats, &fewer, 3_000).unwrap();
    assert_eq!(catalog.count(Section::Live, None).unwrap(), 1);
    assert_eq!(catalog.count(Section::Movies, None).unwrap(), 1);
    assert_eq!(
        catalog
            .item(Section::Movies, 5)
            .unwrap()
            .unwrap()
            .extension
            .as_deref(),
        Some("mkv")
    );
}

#[test]
fn search_ignores_case_and_accents() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let (cats, streams) = sample_live();
    catalog.store_live(&cats, &streams, 1_000).unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 5, "name": "Le Fabuleux Destin d'Amélie Poulain"},
        {"stream_id": 6, "name": "100% Amélie"}
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 1_000).unwrap();

    let hits: Vec<_> = catalog
        .search(Section::Movies, "AMELIE destin", 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(hits, [5]);
    assert_eq!(
        catalog.search(Section::Live, "equipe", 10).unwrap()[0].id,
        7
    );
    // % et _ sont cherchés littéralement.
    assert_eq!(
        catalog.search(Section::Movies, "100%", 10).unwrap().len(),
        1
    );
    assert!(
        catalog
            .search(Section::Movies, "   ", 10)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn search_puts_the_whole_query_first() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 1, "name": "FR - Le Retour du roi (version longue)"},
        {"stream_id": 2, "name": "FR - Retour vers le futur"},
        {"stream_id": 3, "name": "FR - Au-delà : retour vers"},
        {"stream_id": 4, "name": "FR - Détour vers l'ouest"},
        {"stream_id": 5, "name": "FR - Retour vers le futur II"},
        {"stream_id": 6, "name": "FR:Retour vers le futur III"}
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 1_000).unwrap();

    let hits: Vec<_> = catalog
        .search(Section::Movies, "retour  VERS", 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    // Requête en début de mot, même collée à une décoration (ordre
    // alphabétique), puis tous les mots ailleurs.
    assert_eq!(hits, [3, 2, 5, 6, 1]);
}

#[test]
fn refresh_is_due_after_24_hours() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    assert!(catalog.needs_refresh(Section::Series, 0).unwrap());
    catalog.store_series(&[], &[], 10_000).unwrap();
    assert!(
        !catalog
            .needs_refresh(Section::Series, 10_000 + MAX_AGE_SECS - 1)
            .unwrap()
    );
    assert!(
        catalog
            .needs_refresh(Section::Series, 10_000 + MAX_AGE_SECS)
            .unwrap()
    );
    // Horloge reculée : on rafraîchit plutôt que de faire confiance au cache.
    assert!(catalog.needs_refresh(Section::Series, 9_000).unwrap());
}

#[test]
fn catalog_is_up_to_date_as_of_its_oldest_section() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    assert_eq!(catalog.updated_at().unwrap(), None);
    catalog.store_series(&[], &[], 10_000).unwrap();
    let (cats, streams) = sample_live();
    catalog.store_live(&cats, &streams, 12_000).unwrap();
    assert_eq!(catalog.updated_at().unwrap(), Some(10_000));
    catalog.store_series(&[], &[], 13_000).unwrap();
    assert_eq!(catalog.updated_at().unwrap(), Some(12_000));
}

#[test]
fn reopening_a_file_keeps_data() {
    let dir = std::env::temp_dir().join(format!("hs-catalog-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    {
        let mut catalog = Catalog::open(&path).unwrap();
        let (cats, streams) = sample_live();
        catalog.store_live(&cats, &streams, 1_000).unwrap();
    }
    let catalog = Catalog::open(&path).unwrap();
    assert_eq!(catalog.count(Section::Live, None).unwrap(), 3);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn items_without_categories_stay_due_for_refresh() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let movies: Vec<Movie> = from_value(json!([{"stream_id": 5, "name": "Amélie"}])).unwrap();
    catalog.store_movies(&[], &movies, 1_000).unwrap();
    assert_eq!(catalog.count(Section::Movies, None).unwrap(), 1);
    assert!(catalog.needs_refresh(Section::Movies, 1_001).unwrap());
}

#[test]
fn categories_without_items_stay_due_for_refresh() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let cats = categories(json!([{"category_id": "1", "category_name": "Action"}]));
    catalog.store_movies(&cats, &[], 1_000).unwrap();
    assert!(catalog.needs_refresh(Section::Movies, 1_001).unwrap());

    // Section vraiment vide : pas de nouveau téléchargement avant 24 h.
    catalog.store_series(&[], &[], 1_000).unwrap();
    assert!(!catalog.needs_refresh(Section::Series, 1_001).unwrap());
}

#[test]
fn stored_names_drop_the_french_mention_and_search_on_them() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let movies: Vec<Movie> = serde_json::from_value(serde_json::json!([
        {"stream_id": 1, "name": "FR - Dune (2021)"},
        {"stream_id": 2, "name": "EN - Dune (2021)"},
        {"stream_id": 3, "name": "FR -4K Gravity (2013)"},
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 0).unwrap();
    let names: Vec<String> = catalog
        .search(Section::Movies, "dune", 10)
        .unwrap()
        .into_iter()
        .map(|i| i.name)
        .collect();
    assert!(names.contains(&"Dune (2021)".to_owned()));
    assert!(names.contains(&"EN - Dune (2021)".to_owned()));
    // « fr » ne correspond plus à tous les titres français.
    assert!(
        catalog
            .search(Section::Movies, "fr", 10)
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        catalog.search(Section::Movies, "gravity", 10).unwrap()[0].name,
        "4K Gravity (2013)"
    );
}

#[test]
fn names_stored_by_an_older_version_are_cleaned_on_open() {
    let dir = std::env::temp_dir().join(format!("hs-names-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    {
        let mut catalog = Catalog::open(&path).unwrap();
        let live: Vec<LiveStream> =
            serde_json::from_value(serde_json::json!([{"stream_id": 1, "name": "|FR| CANAL+"}]))
                .unwrap();
        catalog.store_live(&[], &live, 0).unwrap();
    }
    // Ligne écrite avant la migration 5 : nom brut, pas de `raw_name`.
    {
        let conn = rusqlite::Connection::open(&path).unwrap();
        conn.execute_batch(
            "UPDATE item SET name = '|FR| CANAL+', search_key = '|fr| canal+', raw_name = NULL;",
        )
        .unwrap();
    }
    let catalog = Catalog::open(&path).unwrap();
    let found = catalog.search(Section::Live, "canal", 10).unwrap();
    assert_eq!(found[0].name, "CANAL+");
    std::fs::remove_dir_all(&dir).unwrap();
}

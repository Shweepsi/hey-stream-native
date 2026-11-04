use serde_json::{from_value, json};
use xtream_core::{
    Catalog, Category, LiveStream, MAX_AGE_SECS, Movie, RefreshOutcome, Section, Series,
};

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
    // Noms qui commencent par la requête, même derrière une décoration
    // collée (ordre alphabétique), puis la requête en début de mot, puis
    // tous les mots ailleurs.
    assert_eq!(hits, [2, 5, 6, 3, 1]);
}

#[test]
fn search_puts_names_starting_with_the_query_first() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 1, "name": "4K Dune.Part.Two.2024.2160p.WEB-DL"},
        {"stream_id": 2, "name": "4KL Dune"},
        {"stream_id": 3, "name": "Chroniques.dune.famille.2012.1080p"},
        {"stream_id": 4, "name": "Dune (1984)"},
        {"stream_id": 5, "name": "|FR| ★ Dune : Deuxième partie (2024)"},
        {"stream_id": 6, "name": "Jodorowsky's Dune (VOST)"}
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 1_000).unwrap();

    let hits: Vec<_> = catalog
        .search(Section::Movies, "dune", 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    // Les titres « Dune… » d'abord (« 4K » et « 4KL » sont retirés des noms),
    // puis « dune » en début de mot.
    assert_eq!(hits, [2, 4, 5, 1, 3, 6]);

    // Une requête qui commence par une décoration (« ( ») la perd aussi.
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 7, "name": "1 (500) jours"},
        {"stream_id": 8, "name": "(500) jours ensemble"}
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 2_000).unwrap();
    let hits: Vec<_> = catalog
        .search(Section::Movies, "(500) jours", 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(hits, [8, 7]);
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
        "Gravity (2013)"
    );
    // La qualité quitte le nom affiché mais se cherche encore.
    assert_eq!(
        catalog.search(Section::Movies, "4k gravity", 10).unwrap()[0].id,
        3
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

#[test]
fn names_cleaned_with_older_rules_are_cleaned_again_on_open() {
    let dir = std::env::temp_dir().join(format!("hs-names6-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    {
        let mut catalog = Catalog::open(&path).unwrap();
        let movies: Vec<Movie> = serde_json::from_value(serde_json::json!([
            {"stream_id": 1, "name": "4KL Apollo 13"},
        ]))
        .unwrap();
        catalog.store_movies(&[], &movies, 0).unwrap();
    }
    // Ligne nettoyée avant la migration 6, qui gardait la qualité.
    {
        let conn = rusqlite::Connection::open(&path).unwrap();
        conn.execute_batch(
            "UPDATE item SET name = '4KL Apollo 13', search_key = '4kl apollo 13';
             PRAGMA user_version = 5;",
        )
        .unwrap();
    }
    let catalog = Catalog::open(&path).unwrap();
    let found = catalog.search(Section::Movies, "apollo", 10).unwrap();
    assert_eq!(found[0].name, "Apollo 13");
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn movie_and_series_titles_are_cleaned_but_channels_are_not() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let live: Vec<LiveStream> = from_value(json!([
        {"stream_id": 1, "name": "TF1 ᵁᴴᴰ"},
        {"stream_id": 2, "name": "FRANCE 3 FHD"},
    ]))
    .unwrap();
    catalog.store_live(&[], &live, 0).unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 10, "name": "Dune (2021) FHD"},
        {"stream_id": 11, "name": "9 Bullets ( 2022)"},
        {"stream_id": 12, "name": "15.Aout.2001.1080p.TF1.WEB-DL.AAC"},
        {"stream_id": 13, "name": "15 Minutes (2001) MULTI"},
    ]))
    .unwrap();
    catalog.store_movies(&[], &movies, 0).unwrap();
    let series: Vec<Series> = from_value(json!([
        {"series_id": 20, "name": "FR - The Crown (2016) 4K HDR"},
    ]))
    .unwrap();
    catalog.store_series(&[], &series, 0).unwrap();

    let name = |section: Section, id: i64| catalog.item(section, id).unwrap().unwrap().name;
    assert_eq!(name(Section::Live, 1), "TF1 ᵁᴴᴰ");
    assert_eq!(name(Section::Live, 2), "FRANCE 3 FHD");
    assert_eq!(name(Section::Movies, 10), "Dune (2021)");
    assert_eq!(name(Section::Movies, 11), "9 Bullets (2022)");
    assert_eq!(name(Section::Movies, 12), "15 Aout (2001)");
    assert_eq!(name(Section::Movies, 13), "15 Minutes (2001) MULTI");
    assert_eq!(name(Section::Series, 20), "The Crown (2016)");

    // Ce qui a quitté le titre se cherche encore.
    let found = |query: &str| -> Vec<i64> {
        catalog
            .search(Section::Movies, query, 10)
            .unwrap()
            .into_iter()
            .map(|i| i.id)
            .collect()
    };
    assert_eq!(found("dune fhd"), [10]);
    assert_eq!(found("15 aout 2001"), [12]);
    assert_eq!(found("1080p tf1"), [12]);
    assert_eq!(found("bullets"), [11]);
}

#[test]
fn titles_cleaned_with_the_previous_rules_are_cleaned_again_on_open() {
    let dir = std::env::temp_dir().join(format!("hs-names7-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    {
        let mut catalog = Catalog::open(&path).unwrap();
        let live: Vec<LiveStream> =
            from_value(json!([{"stream_id": 3, "name": "TF1 ᵁᴴᴰ"}])).unwrap();
        catalog.store_live(&[], &live, 0).unwrap();
        let movies: Vec<Movie> = from_value(json!([
            {"stream_id": 1, "name": "Dune (2021) FHD"},
            {"stream_id": 2, "name": "Amélie"},
            {"stream_id": 4, "name": "15.Aout.2001.1080p.TF1.WEB-DL"},
            {"stream_id": 5, "name": "FR - Some.Movie.2020.WEB-DL"},
        ]))
        .unwrap();
        catalog.store_movies(&[], &movies, 0).unwrap();
    }
    // Lignes nettoyées par la version précédente (migration 6) : les espaces,
    // les noms de fichiers et les mentions techniques étaient restés.
    {
        let conn = rusqlite::Connection::open(&path).unwrap();
        conn.execute_batch(
            "UPDATE item SET name = 'Dune (2021) FHD' WHERE section = 'movies' AND id = 1;
             UPDATE item SET name = '15.Aout.2001.1080p.TF1.WEB-DL' WHERE section = 'movies' AND id = 4;
             UPDATE item SET name = 'Some.Movie.2020.WEB-DL' WHERE section = 'movies' AND id = 5;
             PRAGMA user_version = 6;",
        )
        .unwrap();
    }
    let catalog = Catalog::open(&path).unwrap();
    let name = |section: Section, id: i64| catalog.item(section, id).unwrap().unwrap().name;
    assert_eq!(name(Section::Movies, 1), "Dune (2021)");
    assert_eq!(name(Section::Movies, 2), "Amélie");
    assert_eq!(name(Section::Movies, 4), "15 Aout (2001)");
    // Un nom de fichier derrière « FR - » est recalculé aussi.
    assert_eq!(name(Section::Movies, 5), "Some Movie (2020)");
    // Les chaînes gardent leur nom.
    assert_eq!(name(Section::Live, 3), "TF1 ᵁᴴᴰ");
    // Le tri suit le nouveau nom : « 15 Aout », « Amélie », « Dune ».
    let order: Vec<i64> = catalog
        .items(Section::Movies, None, 0, 10)
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(order, [4, 2, 1, 5]);
    std::fs::remove_dir_all(&dir).unwrap();
}

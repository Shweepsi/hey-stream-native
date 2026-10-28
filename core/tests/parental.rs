use serde_json::{from_value, json};
use xtream_core::{Catalog, Category, Error, Movie, PlaybackKind, Resume, Section};

/// Films : une catégorie « Action », une catégorie adulte, une « Arabe ».
fn catalog() -> Catalog {
    let mut catalog = Catalog::open_in_memory().unwrap();
    let cats: Vec<Category> = from_value(json!([
        {"category_id": "1", "category_name": "|FR| Action"},
        {"category_id": "2", "category_name": "XXX Adultes"},
        {"category_id": "3", "category_name": "|AR| Films"}
    ]))
    .unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 10, "name": "Taxi", "category_id": "1"},
        {"stream_id": 20, "name": "Film adulte taxi", "category_id": "2"},
        {"stream_id": 30, "name": "Taxi arabe", "category_id": "3"}
    ]))
    .unwrap();
    catalog.store_movies(&cats, &movies, 0).unwrap();
    catalog
}

fn ids(items: Vec<xtream_core::CatalogItem>) -> Vec<i64> {
    items.into_iter().map(|i| i.id).collect()
}

#[test]
fn adult_content_is_locked_everywhere_until_pin() {
    let mut c = catalog();
    let adult = c
        .categories(Section::Movies)
        .unwrap()
        .into_iter()
        .find(|c| c.is_adult)
        .unwrap();
    assert!(adult.locked);

    assert!(matches!(
        c.items(Section::Movies, Some("2"), 0, 10),
        Err(Error::ParentalLock)
    ));
    assert_eq!(
        ids(c.items(Section::Movies, None, 0, 10).unwrap()),
        [10, 30]
    );
    assert_eq!(c.count(Section::Movies, None).unwrap(), 2);
    assert_eq!(
        ids(c.search(Section::Movies, "taxi", 10).unwrap()),
        [10, 30]
    );
    assert!(c.item(Section::Movies, 20).unwrap().is_none());

    // Favori, récent et reprise d'un film adulte : masqués aussi.
    c.toggle_favorite(Section::Movies, 20, 1).unwrap();
    c.mark_watched(Section::Movies, 20, 1).unwrap();
    let resume = Resume {
        kind: PlaybackKind::Movie,
        id: 20,
        position_ms: 600_000,
        duration_ms: 6_000_000,
        title: "Film adulte".into(),
        image: None,
        extension: None,
        series_id: None,
    };
    c.save_progress(&resume, 1).unwrap();
    assert!(c.favorites(Section::Movies, 0, 10).unwrap().is_empty());
    assert!(c.recents(Section::Movies, 10).unwrap().is_empty());
    assert!(c.continue_watching(10).unwrap().is_empty());

    // Pas de code défini : impossible de déverrouiller.
    assert!(matches!(c.unlock_parental("1234", 0), Err(Error::WrongPin)));
    c.set_pin("1234", None).unwrap();
    c.unlock_parental("1234", 0).unwrap();
    assert!(
        !c.categories(Section::Movies)
            .unwrap()
            .iter()
            .any(|c| c.locked)
    );
    assert_eq!(c.items(Section::Movies, Some("2"), 0, 10).unwrap().len(), 1);
    assert_eq!(c.favorites(Section::Movies, 0, 10).unwrap().len(), 1);
    assert_eq!(c.continue_watching(10).unwrap().len(), 1);

    c.lock_parental();
    assert!(matches!(
        c.items(Section::Movies, Some("2"), 0, 10),
        Err(Error::ParentalLock)
    ));
}

#[test]
fn pin_rules() {
    let mut c = catalog();
    assert!(!c.has_pin().unwrap());
    assert!(matches!(c.set_pin("12a4", None), Err(Error::InvalidPin)));
    assert!(matches!(c.set_pin("12345", None), Err(Error::InvalidPin)));
    c.set_pin("0000", None).unwrap();
    assert!(c.has_pin().unwrap());

    // Changer le code exige l'ancien.
    assert!(matches!(c.set_pin("1111", None), Err(Error::WrongPin)));
    assert!(matches!(
        c.set_pin("1111", Some("9999")),
        Err(Error::WrongPin)
    ));
    c.set_pin("1111", Some("0000")).unwrap();
    assert!(matches!(c.unlock_parental("0000", 0), Err(Error::WrongPin)));
}

#[test]
fn too_many_wrong_pins_block_for_30_seconds() {
    let mut c = catalog();
    c.set_pin("4321", None).unwrap();
    for _ in 0..5 {
        assert!(matches!(
            c.unlock_parental("0000", 100),
            Err(Error::WrongPin)
        ));
    }
    // Même le bon code est refusé pendant le blocage.
    assert!(matches!(
        c.unlock_parental("4321", 110),
        Err(Error::TooManyAttempts { retry_in_secs: 20 })
    ));
    c.unlock_parental("4321", 130).unwrap();
    assert!(c.is_parental_unlocked());
}

#[test]
fn hidden_categories_leave_browsing_and_search_only() {
    let mut c = catalog();
    c.toggle_favorite(Section::Movies, 30, 1).unwrap();
    c.set_category_hidden(Section::Movies, "3", true).unwrap();

    let visible: Vec<_> = c
        .categories(Section::Movies)
        .unwrap()
        .into_iter()
        .map(|c| c.id)
        .collect();
    assert_eq!(visible, ["1", "2"]);
    let all = c.all_categories(Section::Movies).unwrap();
    assert_eq!(all.len(), 3);
    assert!(all.iter().find(|c| c.id == "3").unwrap().hidden);

    assert_eq!(ids(c.items(Section::Movies, None, 0, 10).unwrap()), [10]);
    assert_eq!(ids(c.search(Section::Movies, "taxi", 10).unwrap()), [10]);
    // Choisi explicitement : le favori reste.
    assert_eq!(ids(c.favorites(Section::Movies, 0, 10).unwrap()), [30]);

    // Survit à un rafraîchissement, puis se réaffiche.
    let cats: Vec<Category> =
        from_value(json!([{"category_id": "3", "category_name": "|AR| Films"}])).unwrap();
    let movies: Vec<Movie> =
        from_value(json!([{"stream_id": 30, "name": "Taxi arabe", "category_id": "3"}])).unwrap();
    c.store_movies(&cats, &movies, 10).unwrap();
    assert!(c.categories(Section::Movies).unwrap().is_empty());
    c.set_category_hidden(Section::Movies, "3", false).unwrap();
    assert_eq!(c.categories(Section::Movies).unwrap().len(), 1);
}

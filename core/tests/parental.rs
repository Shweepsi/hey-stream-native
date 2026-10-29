use serde_json::{from_value, json};
use xtream_core::{
    Catalog, Category, Error, LiveStream, Movie, PlaybackKind, Resume, Section, Series,
};

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
    c.add_recent(Section::Movies, 20, 1).unwrap();
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
    assert!(c.continue_watching(None, 10).unwrap().is_empty());

    // Pas de code défini : impossible de déverrouiller.
    assert!(matches!(c.unlock_parental("1234", 0), Err(Error::WrongPin)));
    c.set_pin("1234", None, 0).unwrap();
    c.unlock_parental("1234", 0).unwrap();
    assert!(
        !c.categories(Section::Movies)
            .unwrap()
            .iter()
            .any(|c| c.locked)
    );
    assert_eq!(c.items(Section::Movies, Some("2"), 0, 10).unwrap().len(), 1);
    assert_eq!(c.favorites(Section::Movies, 0, 10).unwrap().len(), 1);
    assert_eq!(c.continue_watching(None, 10).unwrap().len(), 1);

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
    assert!(matches!(c.set_pin("12a4", None, 0), Err(Error::InvalidPin)));
    assert!(matches!(
        c.set_pin("12345", None, 0),
        Err(Error::InvalidPin)
    ));
    c.set_pin("0000", None, 0).unwrap();
    assert!(c.has_pin().unwrap());

    // Changer le code exige l'ancien.
    assert!(matches!(c.set_pin("1111", None, 0), Err(Error::WrongPin)));
    assert!(matches!(
        c.set_pin("1111", Some("9999"), 0),
        Err(Error::WrongPin)
    ));
    c.set_pin("1111", Some("0000"), 0).unwrap();
    assert!(matches!(c.unlock_parental("0000", 0), Err(Error::WrongPin)));
}

#[test]
fn too_many_wrong_pins_block_for_30_seconds() {
    let mut c = catalog();
    c.set_pin("4321", None, 0).unwrap();
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
fn lockout_ends_with_a_fresh_set_of_attempts() {
    let mut c = catalog();
    c.set_pin("4321", None, 0).unwrap();
    for _ in 0..5 {
        assert!(matches!(
            c.unlock_parental("0000", 100),
            Err(Error::WrongPin)
        ));
    }
    // Blocage écoulé : une faute de frappe ne rebloque pas aussitôt.
    assert!(matches!(
        c.unlock_parental("0000", 140),
        Err(Error::WrongPin)
    ));
    c.unlock_parental("4321", 141).unwrap();
}

/// Reprise à 10 min d'un film ou d'un épisode.
fn watch(c: &mut Catalog, kind: PlaybackKind, id: i64, series_id: Option<i64>) {
    let resume = Resume {
        kind,
        id,
        position_ms: 600_000,
        duration_ms: 6_000_000,
        title: id.to_string(),
        image: None,
        extension: None,
        series_id,
    };
    c.save_progress(&resume, id).unwrap();
}

#[test]
fn continue_watching_is_locked_like_the_lists() {
    let mut c = Catalog::open_in_memory().unwrap();
    let cats: Vec<Category> =
        from_value(json!([{"category_id": "5", "category_name": "VOD Premium"}])).unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 1, "name": "Catégorie inconnue", "category_id": "99"},
        {"stream_id": 2, "name": "Sans catégorie"},
        {"stream_id": 3, "name": "Marqué", "category_id": "5", "is_adult": "1"},
        {"stream_id": 4, "name": "Ordinaire", "category_id": "5"}
    ]))
    .unwrap();
    c.store_movies(&cats, &movies, 0).unwrap();
    let cats: Vec<Category> = from_value(json!([
        {"category_id": "7", "category_name": "Séries"},
        {"category_id": "8", "category_name": "+18"}
    ]))
    .unwrap();
    let series: Vec<Series> = from_value(json!([
        {"series_id": 50, "name": "Marquée", "category_id": "7", "is_adult": 1},
        {"series_id": 60, "name": "Ordinaire", "category_id": "7"},
        {"series_id": 70, "name": "Adulte", "category_id": "8"}
    ]))
    .unwrap();
    c.store_series(&cats, &series, 0).unwrap();
    for id in [1, 2, 3, 4, 999] {
        watch(&mut c, PlaybackKind::Movie, id, None);
    }
    for series_id in [50, 60, 70] {
        watch(
            &mut c,
            PlaybackKind::Episode,
            series_id * 10 + 1,
            Some(series_id),
        );
    }

    let shown = |c: &Catalog| {
        let mut ids: Vec<i64> = c
            .continue_watching(None, 20)
            .unwrap()
            .into_iter()
            .map(|e| e.resume.id)
            .collect();
        ids.sort();
        ids
    };
    // Verrou fermé : ni marqués, ni catégorie adulte ou inconnue, ni absents
    // du catalogue.
    assert_eq!(shown(&c), [2, 4, 601]);
    c.set_pin("1234", None, 0).unwrap();
    c.unlock_parental("1234", 0).unwrap();
    assert_eq!(shown(&c), [1, 2, 3, 4, 501, 601, 701, 999]);
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

#[test]
fn unknown_category_or_provider_flag_stays_locked() {
    let mut c = Catalog::open_in_memory().unwrap();
    // Catégories de films toutes perdues (réponse vide) : rien ne passe tant
    // que le verrou est actif, sauf les entrées sans catégorie.
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 1, "name": "Inconnu", "category_id": "99"},
        {"stream_id": 2, "name": "Sans catégorie"},
        {"stream_id": 3, "name": "Marqué", "is_adult": "1"}
    ]))
    .unwrap();
    c.store_movies(&[], &movies, 0).unwrap();
    assert_eq!(ids(c.items(Section::Movies, None, 0, 10).unwrap()), [2]);
    assert_eq!(c.count(Section::Movies, None).unwrap(), 1);
    assert!(c.search(Section::Movies, "inconnu", 10).unwrap().is_empty());

    // Flux marqué adulte dans une catégorie ordinaire.
    let cats: Vec<Category> =
        from_value(json!([{"category_id": "5", "category_name": "VOD Premium"}])).unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 4, "name": "Film", "category_id": "5"},
        {"stream_id": 5, "name": "Film marqué", "category_id": "5", "is_adult": 1}
    ]))
    .unwrap();
    c.store_movies(&cats, &movies, 10).unwrap();
    assert_eq!(
        ids(c.items(Section::Movies, Some("5"), 0, 10).unwrap()),
        [4]
    );

    c.set_pin("1234", None, 0).unwrap();
    c.unlock_parental("1234", 0).unwrap();
    assert_eq!(
        ids(c.items(Section::Movies, Some("5"), 0, 10).unwrap()),
        [4, 5]
    );
}

#[test]
fn changing_the_pin_cannot_be_used_to_guess_it_and_lockout_survives_restart() {
    let dir = std::env::temp_dir().join(format!("hs-pin-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    {
        let mut c = Catalog::open(&path).unwrap();
        c.set_pin("4321", None, 0).unwrap();
        for guess in ["0000", "0001", "0002", "0003", "0004"] {
            assert!(matches!(
                c.set_pin("9999", Some(guess), 100),
                Err(Error::WrongPin)
            ));
        }
        assert!(matches!(
            c.set_pin("9999", Some("4321"), 101),
            Err(Error::TooManyAttempts { .. })
        ));
    }
    // Relancer l'app ne remet pas le compteur à zéro.
    let mut c = Catalog::open(&path).unwrap();
    assert!(matches!(
        c.unlock_parental("4321", 105),
        Err(Error::TooManyAttempts { retry_in_secs: 25 })
    ));
    // Une horloge reculée ne prolonge pas le blocage indéfiniment.
    c.unlock_parental("4321", 131).unwrap();
    c.set_pin("9999", Some("4321"), 140).unwrap();
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn hiding_everything_does_not_defeat_the_empty_response_guard() {
    let mut c = catalog();
    for id in ["1", "2", "3"] {
        c.set_category_hidden(Section::Movies, id, true).unwrap();
    }
    assert_eq!(c.count(Section::Movies, None).unwrap(), 0);
    // Réponse intermittente sans catégories : le cache est gardé, marquage
    // adulte compris.
    let movies: Vec<Movie> =
        from_value(json!([{"stream_id": 20, "name": "Film adulte taxi", "category_id": "2"}]))
            .unwrap();
    assert_eq!(
        c.store_movies(&[], &movies, 5).unwrap(),
        xtream_core::RefreshOutcome::KeptCache
    );
    assert!(
        c.all_categories(Section::Movies)
            .unwrap()
            .iter()
            .any(|cat| cat.is_adult)
    );
}

fn key(url: &str, user: &str, pass: &str) -> xtream_core::AccountKey {
    xtream_core::XtreamClient::new(&xtream_core::Credentials {
        server_url: url.into(),
        username: user.into(),
        password: pass.into(),
    })
    .unwrap()
    .account_key()
}

#[test]
fn switching_account_clears_everything() {
    use xtream_core::AccountMatch;
    let a = key("panel.example", "alice", "p");
    let mut c = catalog();
    // Cache sans abonnement (neuf, ou restes d'une déconnexion) : effacé.
    assert_eq!(c.bind_account(&a).unwrap(), AccountMatch::Unbound);
    assert_eq!(c.count(Section::Movies, None).unwrap(), 0);
    let cats: Vec<Category> =
        from_value(json!([{"category_id": "1", "category_name": "Action"}])).unwrap();
    let movies: Vec<Movie> =
        from_value(json!([{"stream_id": 10, "name": "Taxi", "category_id": "1"}])).unwrap();
    c.store_movies(&cats, &movies, 0).unwrap();
    c.toggle_favorite(Section::Movies, 10, 1).unwrap();
    c.set_watched(PlaybackKind::Movie, 10, None, true, 1)
        .unwrap();
    c.set_pin("1234", None, 0).unwrap();

    // Même compte : rien n'est effacé, et rien n'est écrit.
    assert_eq!(c.account_match(&a).unwrap(), AccountMatch::Same);
    assert_eq!(c.bind_account(&a).unwrap(), AccountMatch::Same);
    assert_eq!(c.count(Section::Movies, None).unwrap(), 1);

    // Même identifiant, autre adresse : catalogue à recharger, le reste gardé.
    let a2 = key("autre.example", "alice", "p");
    assert_eq!(c.bind_account(&a2).unwrap(), AccountMatch::OtherServer);
    assert!(c.needs_refresh(Section::Movies, 1).unwrap());
    assert_eq!(c.count(Section::Movies, None).unwrap(), 1);
    assert!(c.is_favorite(Section::Movies, 10).unwrap());
    assert!(c.is_watched(PlaybackKind::Movie, 10).unwrap());
    assert!(c.has_pin().unwrap());
    assert_eq!(c.account_match(&a2).unwrap(), AccountMatch::Same);

    // Autre identifiant : tout est effacé.
    let b = key("autre.example", "bob", "p");
    assert_eq!(c.account_match(&b).unwrap(), AccountMatch::OtherUser);
    assert_eq!(c.bind_account(&b).unwrap(), AccountMatch::OtherUser);
    assert_eq!(c.count(Section::Movies, None).unwrap(), 0);
    assert!(!c.is_favorite(Section::Movies, 10).unwrap());
    assert!(!c.is_watched(PlaybackKind::Movie, 10).unwrap());
    assert!(!c.has_pin().unwrap());
    assert_eq!(c.bind_account(&b).unwrap(), AccountMatch::Same);

    // Déconnexion : le cache n'appartient plus à personne.
    c.clear_all().unwrap();
    assert_eq!(c.account_match(&b).unwrap(), AccountMatch::Unbound);
}

#[test]
fn account_key_ignores_password_but_not_server_or_user() {
    let a = key("panel.example:8080", "u", "p1");
    assert_eq!(a, key("http://panel.example:8080/", "u", "p2"));
    assert_ne!(a, key("panel.example:8080", "v", "p1"));
    assert_ne!(a, key("other.example:8080", "u", "p1"));
    let text = format!("{a:?}");
    assert!(!text.contains("panel") && !text.contains("p1"));
}

#[test]
fn account_info_is_cached_for_offline_use() {
    use xtream_core::{AccountInfo, AccountMatch, LiveFormat, ServerInfo};
    let dir = std::env::temp_dir().join(format!("hs-account-info-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("catalog.db");
    let a = key("panel.example", "alice", "p");
    let mut c = Catalog::open(&path).unwrap();
    assert_eq!(c.bind_account(&a).unwrap(), AccountMatch::Unbound);
    assert_eq!(c.cached_account().unwrap(), None);
    assert_eq!(c.live_format(LiveFormat::Ts).unwrap(), LiveFormat::Ts);

    let info = AccountInfo {
        expires_at: Some(1_893_456_000),
        max_connections: Some(1),
        active_connections: Some(0),
        allowed_output_formats: vec!["m3u8".into()],
        server: ServerInfo::default(),
    };
    c.save_account(&info, 500).unwrap();
    drop(c);

    // Rouvert sans réseau : l'accueil a sa date d'expiration.
    let mut c = Catalog::open(&path).unwrap();
    let cached = c.cached_account().unwrap().unwrap();
    assert_eq!(cached.expires_at, Some(1_893_456_000));
    assert_eq!(cached.checked_at, 500);
    // Le fournisseur n'autorise que le HLS.
    assert_eq!(c.live_format(LiveFormat::Ts).unwrap(), LiveFormat::Hls);

    // Même identifiant, autre adresse : gardé ; déconnexion : effacé.
    let a2 = key("autre.example", "alice", "p");
    assert_eq!(c.bind_account(&a2).unwrap(), AccountMatch::OtherServer);
    assert!(c.cached_account().unwrap().is_some());
    c.clear_all().unwrap();
    assert_eq!(c.cached_account().unwrap(), None);
    drop(c);
    std::fs::remove_dir_all(&dir).unwrap();
}

/// Direct : « |FR| Charme » (2) n'a que des flux marqués adultes par le
/// fournisseur, sans aucun marqueur dans son nom ; « |FR| Sport » (1) est
/// ordinaire, « |FR| Ciné » (3) mêle flux marqués et ordinaires.
fn live_with_flagged_category(c: &mut Catalog, charme: serde_json::Value, now: i64) {
    let cats: Vec<Category> = from_value(json!([
        {"category_id": "1", "category_name": "|FR| Sport"},
        {"category_id": "2", "category_name": "|FR| Charme"},
        {"category_id": "3", "category_name": "|FR| Ciné"}
    ]))
    .unwrap();
    let mut streams = vec![
        json!({"stream_id": 1, "name": "Sport 1", "num": 1, "category_id": "1"}),
        json!({"stream_id": 3, "name": "Ciné 1", "num": 3, "category_id": "3"}),
        json!({"stream_id": 4, "name": "Ciné nuit", "num": 4, "category_id": "3", "is_adult": "1"}),
    ];
    streams.extend(charme.as_array().unwrap().iter().cloned());
    let streams: Vec<LiveStream> = from_value(serde_json::Value::Array(streams)).unwrap();
    c.store_live(&cats, &streams, now).unwrap();
}

fn category(c: &Catalog, section: Section, id: &str) -> xtream_core::CachedCategory {
    c.all_categories(section)
        .unwrap()
        .into_iter()
        .find(|cat| cat.id == id)
        .unwrap()
}

#[test]
fn category_whose_items_are_all_flagged_is_adult() {
    let mut c = Catalog::open_in_memory().unwrap();
    live_with_flagged_category(
        &mut c,
        json!([
            {"stream_id": 20, "name": "Charme 1", "num": 20, "category_id": "2", "is_adult": "1"},
            {"stream_id": 21, "name": "Charme 2", "num": 21, "category_id": "2", "is_adult": 1}
        ]),
        0,
    );

    // Listée, verrouillée et adulte, comme une catégorie détectée par son nom.
    let charme = category(&c, Section::Live, "2");
    assert!(charme.is_adult && charme.locked && !charme.hidden);
    assert_eq!(c.categories(Section::Live).unwrap().len(), 3);
    // Pas de compte « 0 » ni de liste vide : l'ouvrir demande le code.
    assert!(matches!(
        c.count(Section::Live, Some("2")),
        Err(Error::ParentalLock)
    ));
    assert!(matches!(
        c.items(Section::Live, Some("2"), 0, 10),
        Err(Error::ParentalLock)
    ));
    // Son contenu n'apparaît nulle part ailleurs.
    assert_eq!(ids(c.items(Section::Live, None, 0, 10).unwrap()), [1, 3]);
    assert!(c.search(Section::Live, "charme", 10).unwrap().is_empty());
    c.toggle_favorite(Section::Live, 20, 1).unwrap();
    c.add_recent(Section::Live, 21, 1).unwrap();
    assert!(c.favorites(Section::Live, 0, 10).unwrap().is_empty());
    assert!(c.recents(Section::Live, 10).unwrap().is_empty());

    // Catégorie mixte : inchangée, ouverte, seules les entrées marquées
    // sont cachées.
    let cine = category(&c, Section::Live, "3");
    assert!(!cine.is_adult && !cine.locked);
    assert_eq!(ids(c.items(Section::Live, Some("3"), 0, 10).unwrap()), [3]);
    assert_eq!(c.count(Section::Live, Some("3")).unwrap(), 1);
    assert!(!category(&c, Section::Live, "1").is_adult);

    // Code saisi : la catégorie s'ouvre avec tout son contenu.
    c.set_pin("1234", None, 0).unwrap();
    c.unlock_parental("1234", 0).unwrap();
    let charme = category(&c, Section::Live, "2");
    assert!(charme.is_adult && !charme.locked);
    assert_eq!(
        ids(c.items(Section::Live, Some("2"), 0, 10).unwrap()),
        [20, 21]
    );
    assert_eq!(c.count(Section::Live, Some("2")).unwrap(), 2);
    assert_eq!(
        ids(c.items(Section::Live, Some("3"), 0, 10).unwrap()),
        [3, 4]
    );
    assert_eq!(ids(c.favorites(Section::Live, 0, 10).unwrap()), [20]);

    // Reverrouillé : de nouveau fermée.
    c.lock_parental();
    assert!(category(&c, Section::Live, "2").locked);
    assert!(matches!(
        c.items(Section::Live, Some("2"), 0, 10),
        Err(Error::ParentalLock)
    ));
}

#[test]
fn provider_flagged_category_is_recomputed_on_each_refresh() {
    let mut c = Catalog::open_in_memory().unwrap();
    let all_flagged = json!([
        {"stream_id": 20, "name": "Charme 1", "num": 20, "category_id": "2", "is_adult": "1"}
    ]);
    live_with_flagged_category(&mut c, all_flagged.clone(), 0);
    assert!(category(&c, Section::Live, "2").locked);

    // Même réponse au rafraîchissement suivant : toujours adulte.
    live_with_flagged_category(&mut c, all_flagged.clone(), 10);
    assert!(category(&c, Section::Live, "2").locked);

    // Réponse vide (cache gardé) : le marquage est gardé aussi.
    let outcome = c.store_live(&[], &[], 20).unwrap();
    assert_eq!(outcome, xtream_core::RefreshOutcome::KeptCache);
    assert!(category(&c, Section::Live, "2").locked);

    // Le fournisseur y ajoute un flux ordinaire : la catégorie n'est plus
    // entièrement adulte, elle devient mixte. Elle s'ouvre sans code, mais le
    // flux marqué reste caché tant que le verrou est fermé.
    live_with_flagged_category(
        &mut c,
        json!([
            {"stream_id": 20, "name": "Charme 1", "num": 20, "category_id": "2", "is_adult": "1"},
            {"stream_id": 22, "name": "Magazine", "num": 22, "category_id": "2"}
        ]),
        30,
    );
    let charme = category(&c, Section::Live, "2");
    assert!(!charme.is_adult && !charme.locked);
    assert_eq!(ids(c.items(Section::Live, Some("2"), 0, 10).unwrap()), [22]);
    assert!(c.search(Section::Live, "charme", 10).unwrap().is_empty());

    // De nouveau entièrement marquée : de nouveau verrouillée.
    live_with_flagged_category(&mut c, all_flagged, 40);
    assert!(category(&c, Section::Live, "2").locked);

    // Vide : rien à cacher, elle n'est pas adulte (et son nom non plus).
    live_with_flagged_category(&mut c, json!([]), 50);
    assert!(!category(&c, Section::Live, "2").is_adult);
}

#[test]
fn provider_flag_is_per_section_and_does_not_unmark_adult_names() {
    let mut c = Catalog::open_in_memory().unwrap();
    live_with_flagged_category(
        &mut c,
        json!([{"stream_id": 20, "name": "Charme 1", "category_id": "2", "is_adult": "1"}]),
        0,
    );
    // Même identifiant de catégorie dans les films : ordinaire ; une
    // catégorie adulte par son nom le reste, même sans entrée marquée.
    let cats: Vec<Category> = from_value(json!([
        {"category_id": "2", "category_name": "Comédies"},
        {"category_id": "8", "category_name": "XXX Adultes"}
    ]))
    .unwrap();
    let movies: Vec<Movie> = from_value(json!([
        {"stream_id": 20, "name": "Comédie", "category_id": "2"},
        {"stream_id": 80, "name": "Film", "category_id": "8"}
    ]))
    .unwrap();
    c.store_movies(&cats, &movies, 0).unwrap();
    assert!(category(&c, Section::Live, "2").locked);
    assert!(!category(&c, Section::Movies, "2").is_adult);
    assert!(category(&c, Section::Movies, "8").locked);

    // Les séries suivent la même règle.
    let cats: Vec<Category> =
        from_value(json!([{"category_id": "4", "category_name": "Soirée"}])).unwrap();
    let series: Vec<Series> = from_value(json!([
        {"series_id": 40, "name": "Série marquée", "category_id": "4", "is_adult": 1}
    ]))
    .unwrap();
    c.store_series(&cats, &series, 0).unwrap();
    assert!(category(&c, Section::Series, "4").locked);
    assert!(matches!(
        c.count(Section::Series, Some("4")),
        Err(Error::ParentalLock)
    ));
}

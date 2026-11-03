use xtream_core::{
    AccountMatch, Catalog, Credentials, Section, TrackChoice, TrackInfo, TrackPreference,
    XtreamClient, choose_tracks,
};

fn track(language: Option<&str>, label: Option<&str>) -> TrackInfo {
    TrackInfo {
        language: language.map(str::to_owned),
        label: label.map(str::to_owned),
        forced: false,
    }
}

fn forced(language: Option<&str>, label: Option<&str>) -> TrackInfo {
    TrackInfo {
        forced: true,
        ..track(language, label)
    }
}

fn choice(audio: Option<usize>, text: Option<usize>, text_off: bool) -> TrackChoice {
    TrackChoice {
        audio,
        text,
        text_off,
    }
}

#[test]
fn auto_asks_for_nothing() {
    let audio = [track(Some("eng"), None), track(Some("fra"), None)];
    let text = [track(Some("fra"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::Auto, "fr", &audio, &text),
        TrackChoice::default()
    );
}

#[test]
fn dubbed_takes_the_french_track_vff_first_and_no_subtitles() {
    let audio = [
        track(Some("eng"), None),
        track(Some("fra"), Some("VFQ")),
        track(Some("fre"), Some("VFF")),
    ];
    let text = [
        track(Some("fra"), Some("Français")),
        track(Some("eng"), None),
    ];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &audio, &text),
        choice(Some(2), None, true)
    );
    // Sans libellé, la première piste française.
    let plain = [track(Some("eng"), None), track(Some("fra"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &plain, &[]),
        choice(Some(1), None, true)
    );
}

#[test]
fn dubbed_keeps_the_forced_french_subtitles() {
    let audio = [track(Some("eng"), None), track(Some("fra"), None)];
    let text = [track(Some("fra"), None), forced(Some("fra"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &audio, &text),
        choice(Some(1), Some(1), false)
    );
    // « forcés » dit par le libellé seul.
    let labelled = [
        track(Some("fra"), Some("Français")),
        track(Some("fra"), Some("Français (forcés)")),
    ];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &audio, &labelled),
        choice(Some(1), Some(1), false)
    );
    // Des sous-titres forcés dans une autre langue ne servent pas.
    let english = [forced(Some("eng"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &audio, &english),
        choice(Some(1), None, true)
    );
}

#[test]
fn dubbed_without_a_french_track_changes_nothing() {
    let audio = [track(Some("eng"), None), track(Some("ita"), None)];
    let text = [track(Some("fra"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &audio, &text),
        TrackChoice::default()
    );
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &[], &[]),
        TrackChoice::default()
    );
}

#[test]
fn original_takes_the_first_foreign_track_with_full_french_subtitles() {
    let audio = [
        track(Some("fra"), Some("VFF")),
        track(Some("eng"), None),
        track(Some("ita"), None),
    ];
    let text = [
        track(Some("eng"), None),
        forced(Some("fra"), None),
        track(Some("fra"), Some("Français SDH")),
        track(Some("fra"), Some("Français")),
    ];
    assert_eq!(
        choose_tracks(TrackPreference::OriginalSubtitled, "fr", &audio, &text),
        choice(Some(1), Some(3), false)
    );
}

#[test]
fn original_falls_back_to_forced_french_subtitles_then_to_the_default() {
    let audio = [track(Some("fra"), None), track(Some("eng"), None)];
    assert_eq!(
        choose_tracks(
            TrackPreference::OriginalSubtitled,
            "fr",
            &audio,
            &[forced(Some("fra"), None)]
        ),
        choice(Some(1), Some(0), false)
    );
    // Aucun sous-titre français : ceux par défaut restent.
    assert_eq!(
        choose_tracks(
            TrackPreference::OriginalSubtitled,
            "fr",
            &audio,
            &[track(Some("eng"), None)]
        ),
        choice(Some(1), None, false)
    );
}

#[test]
fn original_without_another_language_changes_nothing() {
    let only_french = [track(Some("fra"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::OriginalSubtitled, "fr", &only_french, &[]),
        TrackChoice::default()
    );
    // Langue muette mais annoncée « VO » : c'est l'original.
    let unknown = [
        track(Some("und"), Some("VO")),
        track(Some("fra"), Some("VF")),
    ];
    assert_eq!(
        choose_tracks(TrackPreference::OriginalSubtitled, "fr", &unknown, &[]),
        choice(Some(0), None, false)
    );
    // Un code de langue que la liste ne connaît pas est une autre langue.
    let swedish = [track(Some("fra"), None), track(Some("sv"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::OriginalSubtitled, "fr", &swedish, &[]),
        choice(Some(1), None, false)
    );
}

#[test]
fn french_is_read_from_codes_and_from_labels() {
    let cases = [
        (Some("fr"), None),
        (Some("fra"), None),
        (Some("fre"), None),
        (Some("FR"), None),
        (Some("fr-CA"), None),
        (Some("fr_FR"), None),
        (Some(" fr "), None),
        (None, Some("VF")),
        (None, Some("VFF")),
        (None, Some("FRANÇAIS")),
        (None, Some("TrueFrench")),
        (Some("und"), Some("Français")),
        (Some(""), Some("French 5.1")),
    ];
    for (language, label) in cases {
        let audio = [track(Some("eng"), None), track(language, label)];
        assert_eq!(
            choose_tracks(TrackPreference::Dubbed, "fr", &audio, &[]).audio,
            Some(1),
            "{language:?} {label:?}"
        );
    }
    // Ce qui ressemble sans l'être n'est pas du français.
    for (language, label) in [
        (Some("fi"), None),
        (Some("und"), Some("Frasier")),
        (None, Some("Affr")),
    ] {
        let audio = [track(Some("eng"), None), track(language, label)];
        assert_eq!(
            choose_tracks(TrackPreference::Dubbed, "fr", &audio, &[]),
            TrackChoice::default(),
            "{language:?} {label:?}"
        );
    }
}

#[test]
fn the_app_language_decides_what_dubbed_and_original_mean() {
    let audio = [track(Some("fra"), None), track(Some("eng"), None)];
    let text = [track(Some("fra"), None), track(Some("eng"), None)];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "en", &audio, &text),
        choice(Some(1), None, true)
    );
    assert_eq!(
        choose_tracks(TrackPreference::OriginalSubtitled, "EN-us", &audio, &text),
        choice(Some(0), Some(1), false)
    );
    // « fra » à la place de « fr » pour la langue de l'app.
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fra", &audio, &text),
        choice(Some(0), None, true)
    );
}

#[test]
fn audio_description_and_commentary_come_last() {
    let audio = [
        track(Some("fra"), Some("Français audiodescription")),
        track(Some("fra"), Some("Commentaire du réalisateur")),
        track(Some("fra"), Some("Français")),
    ];
    assert_eq!(
        choose_tracks(TrackPreference::Dubbed, "fr", &audio, &[]).audio,
        Some(2)
    );
    // Et ce n'est jamais « l'original » choisi.
    let english = [
        track(Some("fra"), None),
        track(Some("eng"), Some("English audio description")),
        track(Some("eng"), None),
    ];
    assert_eq!(
        choose_tracks(TrackPreference::OriginalSubtitled, "fr", &english, &[]).audio,
        Some(2)
    );
}

fn key(server: &str, user: &str) -> xtream_core::AccountKey {
    XtreamClient::new(&Credentials {
        server_url: server.into(),
        username: user.into(),
        password: "p".into(),
    })
    .unwrap()
    .account_key()
}

#[test]
fn the_preference_and_the_last_category_belong_to_the_account() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    assert_eq!(catalog.track_preference().unwrap(), TrackPreference::Auto);
    assert_eq!(catalog.last_category(Section::Live).unwrap(), None);

    let alice = key("panel.example", "alice");
    assert_eq!(catalog.bind_account(&alice).unwrap(), AccountMatch::Unbound);
    catalog
        .set_track_preference(TrackPreference::Dubbed)
        .unwrap();
    catalog
        .set_last_category(Section::Live, Some("category:12"))
        .unwrap();
    catalog
        .set_last_category(Section::Movies, Some("@continue"))
        .unwrap();
    assert_eq!(catalog.track_preference().unwrap(), TrackPreference::Dubbed);
    catalog
        .set_track_preference(TrackPreference::OriginalSubtitled)
        .unwrap();
    assert_eq!(
        catalog.track_preference().unwrap(),
        TrackPreference::OriginalSubtitled
    );
    assert_eq!(
        catalog.last_category(Section::Live).unwrap().as_deref(),
        Some("category:12")
    );
    assert_eq!(
        catalog.last_category(Section::Movies).unwrap().as_deref(),
        Some("@continue")
    );
    assert_eq!(catalog.last_category(Section::Series).unwrap(), None);

    // Oubliée à la demande.
    catalog.set_last_category(Section::Movies, None).unwrap();
    assert_eq!(catalog.last_category(Section::Movies).unwrap(), None);

    // Même identifiant, autre adresse : gardés.
    assert_eq!(
        catalog
            .bind_account(&key("autre.example", "alice"))
            .unwrap(),
        AccountMatch::OtherServer
    );
    assert_eq!(
        catalog.track_preference().unwrap(),
        TrackPreference::OriginalSubtitled
    );
    assert_eq!(
        catalog.last_category(Section::Live).unwrap().as_deref(),
        Some("category:12")
    );

    // Autre identifiant : effacés.
    assert_eq!(
        catalog.bind_account(&key("autre.example", "bob")).unwrap(),
        AccountMatch::OtherUser
    );
    assert_eq!(catalog.track_preference().unwrap(), TrackPreference::Auto);
    assert_eq!(catalog.last_category(Section::Live).unwrap(), None);

    // Déconnexion : effacés aussi.
    catalog
        .set_track_preference(TrackPreference::Dubbed)
        .unwrap();
    catalog.clear_all().unwrap();
    assert_eq!(catalog.track_preference().unwrap(), TrackPreference::Auto);
}

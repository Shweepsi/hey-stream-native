//! L'API exposée aux interfaces, appelée comme le feraient Kotlin et Swift
//! (méthodes bloquantes, hors de tout runtime async).

use std::sync::Arc;
use std::thread;

use heystream::{
    HeyStreamCore, HeyStreamError, LiveFormat, PlaybackKind, Resume, Section, StreamKind,
};
use serde_json::json;
use wiremock::matchers::{method, query_param, query_param_is_missing};
use wiremock::{Mock, MockServer, ResponseTemplate};

struct Panel {
    server: MockServer,
    // Garde le runtime du faux serveur en vie pendant le test.
    runtime: tokio::runtime::Runtime,
}

impl Panel {
    /// Répond à `action` après `delay_ms`.
    fn route(&self, action: &str, body: serde_json::Value, delay_ms: u64) {
        self.runtime.block_on(
            Mock::given(method("GET"))
                .and(query_param("action", action))
                .respond_with(
                    ResponseTemplate::new(200)
                        .set_body_json(body)
                        .set_delay(std::time::Duration::from_millis(delay_ms)),
                )
                .mount(&self.server),
        );
    }

    /// Accepte la connexion de `user` (les autres reçoivent un 404).
    fn accept(&self, user: &str) {
        self.accept_with(user, json!({"auth": 1, "status": "Active"}));
    }

    fn accept_with(&self, user: &str, user_info: serde_json::Value) {
        self.runtime.block_on(
            Mock::given(method("GET"))
                .and(query_param_is_missing("action"))
                .and(query_param("username", user))
                .respond_with(
                    ResponseTemplate::new(200).set_body_json(json!({"user_info": user_info})),
                )
                .mount(&self.server),
        );
    }
}

fn panel(routes: Vec<(&str, serde_json::Value)>) -> Panel {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let server = runtime.block_on(MockServer::start());
    let panel = Panel { server, runtime };
    for (action, body) in routes {
        panel.route(action, body, 0);
    }
    panel
}

fn open(panel: &Panel, dir: &std::path::Path) -> std::sync::Arc<HeyStreamCore> {
    HeyStreamCore::new(
        panel.server.uri(),
        "u".into(),
        "p".into(),
        dir.to_string_lossy().into(),
    )
    .unwrap()
}

fn temp_dir(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("hs-ffi-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn refresh_browse_and_user_data() {
    let panel = panel(vec![
        (
            "get_live_categories",
            json!([{"category_id": "1", "category_name": "Infos"}]),
        ),
        (
            "get_live_streams",
            json!([{"stream_id": 5, "name": "France Info", "category_id": "1"}]),
        ),
        (
            "get_vod_categories",
            json!([{"category_id": "2", "category_name": "XXX"}]),
        ),
        (
            "get_vod_streams",
            json!([{"stream_id": 9, "name": "Adulte", "category_id": "2"}]),
        ),
        ("get_series_categories", json!([])),
        ("get_series", json!([])),
    ]);
    let dir = temp_dir("refresh");
    let core = open(&panel, &dir);

    let report = core.refresh(false).unwrap();
    assert_eq!(report.len(), 3);
    assert!(report[0].refreshed && report[0].items == 1);
    // À jour : pas de second téléchargement.
    assert!(
        core.refresh(false)
            .unwrap()
            .iter()
            .all(|r| !r.refreshed && !r.failed)
    );

    let live = core.items(Section::Live, Some("1".into()), 0, 50).unwrap();
    assert_eq!(live[0].name, "France Info");
    assert!(core.toggle_favorite(Section::Live, 5).unwrap());
    assert_eq!(core.favorites(Section::Live, 0, 10).unwrap().len(), 1);

    // Catégorie adulte verrouillée jusqu'au code.
    assert!(matches!(
        core.items(Section::Movies, Some("2".into()), 0, 10),
        Err(HeyStreamError::ParentalLock)
    ));
    core.set_pin("1234".into(), None).unwrap();
    core.unlock_parental("1234".into()).unwrap();
    assert_eq!(
        core.items(Section::Movies, Some("2".into()), 0, 10)
            .unwrap()
            .len(),
        1
    );

    core.save_progress(Resume {
        kind: PlaybackKind::Movie,
        id: 9,
        position_ms: 120_000,
        duration_ms: 5_400_000,
        title: "Adulte".into(),
        image: None,
        extension: Some("mp4".into()),
        series_id: None,
    })
    .unwrap();
    assert_eq!(
        core.progress(PlaybackKind::Movie, 9).unwrap(),
        Some(120_000)
    );

    // Réouverture : données conservées, contrôle parental reverrouillé.
    drop(core);
    let core = open(&panel, &dir);
    assert!(core.is_favorite(Section::Live, 5).unwrap());
    assert!(!core.is_parental_unlocked());
    assert!(core.continue_watching(10).unwrap().is_empty());

    assert_eq!(
        core.stream_url(StreamKind::Live, 5, None),
        format!("{}/live/u/p/5.ts", panel.server.uri())
    );
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn refresh_fails_only_when_every_section_fails() {
    // Aucun itinéraire : le faux serveur répond 404 partout.
    let panel = panel(vec![]);
    let dir = temp_dir("fail");
    let core = open(&panel, &dir);
    assert!(matches!(core.refresh(true), Err(HeyStreamError::NotFound)));
    assert!(matches!(core.authenticate(), Err(HeyStreamError::NotFound)));
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn series_and_next_episode() {
    let panel = panel(vec![(
        "get_series_info",
        json!({"info": {"name": "Lupin"}, "episodes": {"1": [{"id": 1, "episode_num": 1}, {"id": 2, "episode_num": 2}]}}),
    )]);
    let dir = temp_dir("series");
    let core = open(&panel, &dir);
    let info = core.series_info(7).unwrap();
    assert_eq!(info.name.as_deref(), Some("Lupin"));
    assert_eq!(core.next_episode(7, 1).unwrap().unwrap().id, 2);
    assert!(core.next_episode(7, 2).unwrap().is_none());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn invalid_server_url_is_reported() {
    let err = HeyStreamCore::new(
        "ftp://x".into(),
        "u".into(),
        "p".into(),
        std::env::temp_dir().to_string_lossy().into(),
    );
    assert!(matches!(err, Err(HeyStreamError::InvalidServerUrl)));
}

/// Faux panneau qui compte les requêtes reçues pour une action.
fn counting_panel(action: &str, body: serde_json::Value, expected: u64) -> Panel {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let server = runtime.block_on(async {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(query_param("action", action))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(body)
                    .set_delay(std::time::Duration::from_millis(200)),
            )
            .expect(expected)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(json!([{"category_id": "1", "category_name": "A"}])),
            )
            .mount(&server)
            .await;
        server
    });
    Panel { server, runtime }
}

#[test]
fn simultaneous_now_next_sends_one_request() {
    let listings = json!({"epg_listings": [{"title": "QQ==", "start_timestamp": 0, "stop_timestamp": 9_999_999_999i64}]});
    let panel = counting_panel("get_short_epg", listings, 1);
    let dir = temp_dir("epg-race");
    let core = open(&panel, &dir);
    let handles: Vec<_> = (0..4)
        .map(|_| {
            let core = Arc::clone(&core);
            thread::spawn(move || core.now_next(7).unwrap())
        })
        .collect();
    for handle in handles {
        handle.join().unwrap();
    }
    // `expect(1)` est vérifié à la fermeture du faux serveur.
    drop(core);
    drop(panel);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn simultaneous_refresh_downloads_once() {
    let panel = counting_panel(
        "get_vod_streams",
        json!([{"stream_id": 1, "name": "A", "category_id": "1"}]),
        1,
    );
    let dir = temp_dir("refresh-race");
    let core = open(&panel, &dir);
    let handles: Vec<_> = (0..3)
        .map(|_| {
            let core = Arc::clone(&core);
            thread::spawn(move || core.refresh(true).unwrap())
        })
        .collect();
    let refreshed: usize = handles
        .into_iter()
        .map(|h| {
            h.join()
                .unwrap()
                .iter()
                .filter(|r| r.section == Section::Movies && r.refreshed)
                .count()
        })
        .sum();
    assert_eq!(refreshed, 1);
    drop(core);
    drop(panel);
    std::fs::remove_dir_all(&dir).unwrap();
}

fn live_panel() -> Panel {
    panel(vec![
        (
            "get_live_categories",
            json!([{"category_id": "1", "category_name": "Infos"}]),
        ),
        (
            "get_live_streams",
            json!([{"stream_id": 5, "name": "France Info", "category_id": "1"}]),
        ),
    ])
}

fn open_as(url: &str, user: &str, dir: &std::path::Path) -> Arc<HeyStreamCore> {
    HeyStreamCore::new(
        url.into(),
        user.into(),
        "p".into(),
        dir.to_string_lossy().into(),
    )
    .unwrap()
}

#[test]
fn account_changes_erase_only_after_a_successful_login() {
    let panel = live_panel();
    panel.accept("u");
    panel.accept("autre");
    let url = panel.server.uri();
    let dir = temp_dir("account");
    let core = open_as(&url, "u", &dir);
    core.refresh(true).unwrap();
    core.toggle_favorite(Section::Live, 5).unwrap();
    drop(core);

    // Même abonnement : tout est conservé.
    assert!(
        open_as(&url, "u", &dir)
            .is_favorite(Section::Live, 5)
            .unwrap()
    );

    // Identifiant mal tapé : connexion refusée, rien n'est effacé ni
    // rafraîchi.
    let typo = open_as(&url, "uu", &dir);
    assert!(typo.authenticate().is_err());
    assert!(typo.account_info().unwrap().is_none());
    assert!(typo.refresh(true).is_err());
    drop(typo);
    assert!(
        open_as(&url, "u", &dir)
            .is_favorite(Section::Live, 5)
            .unwrap()
    );

    // Même identifiant, autre adresse du même fournisseur : favoris gardés,
    // catalogue rechargé.
    let other_address = url.replace("127.0.0.1", "localhost");
    let core = open_as(&other_address, "u", &dir);
    core.authenticate().unwrap();
    assert!(core.is_favorite(Section::Live, 5).unwrap());
    let report = core.refresh(false).unwrap();
    assert!(
        report
            .iter()
            .any(|r| r.section == Section::Live && r.refreshed)
    );
    drop(core);

    // Autre identifiant : tout est effacé une fois la connexion réussie.
    let other = open_as(&url, "autre", &dir);
    other.authenticate().unwrap();
    assert!(!other.is_favorite(Section::Live, 5).unwrap());
    assert_eq!(other.count(Section::Live, None).unwrap(), 0);
    other.refresh(true).unwrap();
    other.toggle_favorite(Section::Live, 5).unwrap();

    // Déconnexion : l'objet ne sert plus, et le cache repart de zéro.
    other.sign_out().unwrap();
    assert!(matches!(
        other.count(Section::Live, None),
        Err(HeyStreamError::SignedOut)
    ));
    let again = open_as(&url, "autre", &dir);
    assert_eq!(again.count(Section::Live, None).unwrap(), 0);
    assert!(!again.is_favorite(Section::Live, 5).unwrap());
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn sign_out_during_a_refresh_stores_nothing() {
    let panel = panel(vec![(
        "get_live_categories",
        json!([{"category_id": "1", "category_name": "Infos"}]),
    )]);
    // Liste lente : la déconnexion arrive pendant le téléchargement.
    panel.route(
        "get_live_streams",
        json!([{"stream_id": 5, "name": "France Info", "category_id": "1"}]),
        800,
    );
    panel.accept("b");
    let url = panel.server.uri();
    let dir = temp_dir("sign-out-race");
    let a = open_as(&url, "a", &dir);
    let refreshing = {
        let a = Arc::clone(&a);
        thread::spawn(move || a.refresh(true))
    };
    thread::sleep(std::time::Duration::from_millis(200));
    a.sign_out().unwrap();
    // Un autre compte se connecte pendant que le téléchargement de A finit.
    let b = open_as(&url, "b", &dir);
    b.authenticate().unwrap();
    assert!(matches!(
        refreshing.join().unwrap(),
        Err(HeyStreamError::SignedOut)
    ));
    assert_eq!(b.count(Section::Live, None).unwrap(), 0);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn a_failed_section_is_not_downloaded_again_at_once() {
    let panel = live_panel();
    panel.route(
        "get_vod_categories",
        json!([{"category_id": "2", "category_name": "Films"}]),
        0,
    );
    panel.runtime.block_on(
        Mock::given(method("GET"))
            .and(query_param("action", "get_vod_streams"))
            .respond_with(ResponseTemplate::new(500))
            .expect(1)
            .mount(&panel.server),
    );
    let dir = temp_dir("retry");
    let core = open(&panel, &dir);
    let report = core.refresh(true).unwrap();
    assert!(
        report
            .iter()
            .any(|r| r.section == Section::Movies && r.failed)
    );
    // Le bouton manuel juste après, ou un appel qui attendait : pas de
    // nouvelle requête pour les films.
    assert!(core.refresh(true).unwrap().iter().all(|r| !r.failed));
    core.refresh(false).unwrap();
    drop(core);
    drop(panel);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn account_info_and_live_format_work_offline() {
    let panel = panel(vec![]);
    panel.accept_with(
        "u",
        json!({"auth": 1, "status": "Active", "exp_date": "1893456000",
               "max_connections": "1", "allowed_output_formats": ["m3u8"]}),
    );
    let url = panel.server.uri();
    let dir = temp_dir("account-info");
    let core = open(&panel, &dir);
    assert!(core.account_info().unwrap().is_none());
    // Formats encore inconnus : la préférence du lecteur.
    assert!(
        core.live_url(5, LiveFormat::Ts)
            .unwrap()
            .ends_with("/live/u/p/5.ts")
    );
    let info = core.authenticate().unwrap();
    assert_eq!(info.expires_at, Some(1_893_456_000));
    drop(core);

    // Fournisseur qui ne répond plus : l'accueil a toujours la date
    // d'expiration, et le direct est demandé dans le seul format autorisé.
    // Le faux serveur reste ouvert (404 partout) : arrêté, son port pourrait
    // être repris par un autre test lancé en parallèle.
    panel.runtime.block_on(panel.server.reset());
    let core = open_as(&url, "u", &dir);
    assert!(core.authenticate().is_err());
    let cached = core.account_info().unwrap().unwrap();
    assert_eq!(cached.expires_at, Some(1_893_456_000));
    assert_eq!(cached.max_connections, Some(1));
    assert_eq!(cached.checked_at, info.checked_at);
    assert!(
        core.live_url(5, LiveFormat::Ts)
            .unwrap()
            .ends_with("/live/u/p/5.m3u8")
    );
    assert!(
        core.live_url(5, LiveFormat::Hls)
            .unwrap()
            .ends_with("/live/u/p/5.m3u8")
    );
    std::fs::remove_dir_all(&dir).unwrap();
}

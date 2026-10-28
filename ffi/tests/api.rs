//! L'API exposée aux interfaces, appelée comme le feraient Kotlin et Swift
//! (méthodes bloquantes, hors de tout runtime async).

use heystream::{
    HeyStreamCore, HeyStreamError, PlaybackKind, Resume, Section, StreamKind, next_episode,
};
use serde_json::json;
use wiremock::matchers::{method, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};

struct Panel {
    server: MockServer,
    // Garde le runtime du faux serveur en vie pendant le test.
    _runtime: tokio::runtime::Runtime,
}

fn panel(routes: Vec<(&str, serde_json::Value)>) -> Panel {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let server = runtime.block_on(async {
        let server = MockServer::start().await;
        for (action, body) in routes {
            Mock::given(method("GET"))
                .and(query_param("action", action))
                .respond_with(ResponseTemplate::new(200).set_body_json(body))
                .mount(&server)
                .await;
        }
        server
    });
    Panel {
        server,
        _runtime: runtime,
    }
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
    assert_eq!(next_episode(info.clone(), 1).unwrap().id, 2);
    assert!(next_episode(info, 2).is_none());
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

use serde_json::{Value, json};
use wiremock::matchers::{method, path, query_param, query_param_is_missing};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{
    Credentials, Error, XtreamClient, clear_recording, export_recording, recording_summary,
};

const USER: &str = "alice42";
const PASS: &str = "s3cretpw";

fn client(server: &MockServer, username: &str, password: &str) -> XtreamClient {
    XtreamClient::new(&Credentials {
        server_url: server.uri(),
        username: username.into(),
        password: password.into(),
    })
    .unwrap()
}

fn temp_dir(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("hs-rec-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

async fn reply(server: &MockServer, action: &str, body: ResponseTemplate) {
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .and(query_param("action", action))
        .respond_with(body)
        .mount(server)
        .await;
}

/// Panneau dont les réponses citent partout l'adresse, l'identifiant et le
/// mot de passe.
async fn panel(username: &str, password: &str) -> MockServer {
    let server = MockServer::start().await;
    let host = server.address().to_string();
    let base = server.uri();
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .and(query_param_is_missing("action"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "user_info": {"username": username, "password": password, "auth": 1,
                          "status": "Active", "exp_date": "1893456000",
                          "message": format!("Bienvenue {username}")},
            "server_info": {"url": host, "port": "8080", "server_protocol": "http",
                            "ip": "203.0.113.7"},
        })))
        .mount(&server)
        .await;
    let streams: Vec<Value> = (1..=1000)
        .map(|i| {
            json!({
                "stream_id": i.to_string(),
                "name": format!("Chaîne {i}"),
                "stream_icon": format!("{base}/logos/{i}.png"),
                "direct_source": format!("{base}/live/{username}/{password}/{i}.ts?token=abc{i}"),
                "custom_sid": format!("{base}/get.php?username={username}&password={password}"),
            })
        })
        .collect();
    reply(
        &server,
        "get_live_streams",
        ResponseTemplate::new(200).set_body_json(streams),
    )
    .await;
    reply(
        &server,
        "get_vod_categories",
        ResponseTemplate::new(200).set_body_json(json!([])),
    )
    .await;
    reply(&server, "get_series", ResponseTemplate::new(503)).await;
    reply(
        &server,
        "get_short_epg",
        ResponseTemplate::new(200).set_body_json(json!({
            "epg_listings": [{"title": "TGUgMjBo", "start_timestamp": "1", "stop_timestamp": 2}]
        })),
    )
    .await;
    server
}

fn read_export(dir: &std::path::Path) -> (String, Vec<Value>) {
    let out = dir.with_extension("export.json");
    let summary = export_recording(dir, &out, 1_700_000_000).unwrap();
    let text = std::fs::read_to_string(&out).unwrap();
    std::fs::remove_file(&out).unwrap();
    let file: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(file["format"], 2);
    let responses = file["responses"].as_array().unwrap().clone();
    assert_eq!(summary.responses as usize, responses.len());
    (text, responses)
}

#[tokio::test]
async fn recording_follows_normal_use_and_hides_the_account() {
    let server = panel(USER, PASS).await;
    let client = client(&server, USER, PASS);
    let dir = temp_dir("normal");

    // Avant l'activation : rien n'est enregistré.
    client.authenticate().await.unwrap();
    assert_eq!(recording_summary(&dir).responses, 0);

    client.start_recording(&dir).unwrap();
    assert!(client.is_recording());
    client.authenticate().await.unwrap();
    assert_eq!(client.live_streams(None).await.unwrap().len(), 1000);
    assert!(client.movie_categories().await.unwrap().is_empty());
    assert!(matches!(client.series(None).await, Err(Error::Server(503))));
    client.short_epg(7).await.unwrap();
    // Un clone du client (celui de la mise à jour du catalogue) enregistre aussi.
    client.clone().short_epg(8).await.unwrap();
    assert_eq!(recording_summary(&dir).responses, 6);

    let (text, responses) = read_export(&dir);
    for secret in [USER, PASS, "127.0.0.1", "203.0.113.7", "abc1"] {
        assert!(
            !text.contains(secret),
            "{secret} présent dans l'enregistrement"
        );
    }
    let actions: Vec<_> = responses.iter().map(|r| r["action"].clone()).collect();
    assert_eq!(
        actions,
        [
            Value::Null,
            json!("get_live_streams"),
            json!("get_vod_categories"),
            json!("get_series"),
            json!("get_short_epg"),
            json!("get_short_epg"),
        ],
        "dans l'ordre des requêtes"
    );
    let auth = &responses[0];
    assert_eq!(auth["body"]["user_info"]["username"], "username");
    assert_eq!(auth["body"]["user_info"]["message"], "Bienvenue username");
    // Types d'origine gardés (texte, nombre).
    assert_eq!(auth["body"]["user_info"]["exp_date"], "1893456000");
    assert_eq!(auth["body"]["user_info"]["auth"], 1);
    assert_eq!(auth["body"]["server_info"]["url"], "example.invalid");
    assert_eq!(auth["body"]["server_info"]["ip"], "192.0.2.1");
    assert!(auth["elapsed_ms"].is_u64() && auth["at"].is_u64());

    let live = &responses[1];
    assert_eq!(live["total"], 1000);
    assert_eq!(live["kept"], 300);
    let first = &live["body"][0];
    assert_eq!(first["stream_icon"], "http://example.invalid/logos/1.png");
    assert_eq!(
        first["direct_source"],
        "http://example.invalid/live/username/password/1.ts?token=secret"
    );
    assert_eq!(live["body"][299]["stream_id"], "997", "échantillon réparti");

    assert_eq!(responses[2]["body"], json!([]));
    assert_eq!(responses[3]["status"], 503);
    assert_eq!(responses[3]["error"], "status");
    assert_eq!(
        responses[4]["params"],
        json!({"stream_id": "7", "limit": "4"})
    );
    assert_eq!(responses[4]["body"]["epg_listings"][0]["title"], "TGUgMjBo");

    client.stop_recording();
    client.short_epg(9).await.unwrap();
    assert_eq!(recording_summary(&dir).responses, 6);

    clear_recording(&dir).unwrap();
    assert_eq!(recording_summary(&dir).responses, 0);
    std::fs::remove_dir_all(&dir).unwrap();
}

#[tokio::test]
async fn old_responses_are_dropped_per_action() {
    let server = panel(USER, PASS).await;
    let client = client(&server, USER, PASS);
    let dir = temp_dir("caps");
    client.start_recording(&dir).unwrap();
    for _ in 0..5 {
        client.movie_categories().await.unwrap();
    }
    for id in 0..45 {
        client.short_epg(id).await.unwrap();
    }
    let (_, responses) = read_export(&dir);
    let count = |action: &str| responses.iter().filter(|r| r["action"] == action).count();
    assert_eq!(count("get_vod_categories"), 3);
    assert_eq!(count("get_short_epg"), 40);
    // Les plus récentes sont gardées.
    let last = responses.last().unwrap();
    assert_eq!(last["params"]["stream_id"], "44");
    std::fs::remove_dir_all(&dir).unwrap();
}

#[tokio::test]
async fn short_credentials_are_hidden_in_urls() {
    let server = panel("ab", "xy").await;
    let client = client(&server, "ab", "xy");
    let dir = temp_dir("short");
    client.start_recording(&dir).unwrap();
    client.live_streams(None).await.unwrap();
    let (_, responses) = read_export(&dir);
    let first = &responses[0]["body"][0];
    assert_eq!(
        first["direct_source"],
        "http://example.invalid/live/username/password/1.ts?token=secret"
    );
    assert_eq!(
        first["custom_sid"],
        "http://example.invalid/get.php?username=username&password=password"
    );
    // Trop courts pour être remplacés dans un texte : « Chaîne » reste intact.
    assert_eq!(first["name"], "Chaîne 1");
    std::fs::remove_dir_all(&dir).unwrap();
}

#[tokio::test]
async fn full_recording_keeps_whole_lists_still_hiding_the_account() {
    let server = panel(USER, PASS).await;
    let client = client(&server, USER, PASS);
    let dir = temp_dir("full");
    // Écriture interrompue d'un lancement précédent : effacée.
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("1-get_vod_streams.json.part"), b"[{").unwrap();
    client.start_full_recording(&dir).unwrap();
    assert!(!dir.join("1-get_vod_streams.json.part").exists());
    client.live_streams(None).await.unwrap();
    for id in 0..45 {
        client.short_epg(id).await.unwrap();
    }
    // Écrit sur un fil à part : l'arrêt attend la fin des écritures.
    client.stop_recording();
    let (text, responses) = read_export(&dir);
    let live = responses
        .iter()
        .find(|r| r["action"] == "get_live_streams")
        .unwrap();
    assert_eq!(live["body"].as_array().unwrap().len(), 1000);
    assert!(live.get("kept").is_none());
    // Plafond ×10 : les 45 guides sont gardés.
    assert_eq!(
        responses
            .iter()
            .filter(|r| r["action"] == "get_short_epg")
            .count(),
        45
    );
    assert!(!text.contains(PASS) && !text.contains(&server.address().to_string()));
    std::fs::remove_dir_all(&dir).unwrap();
}

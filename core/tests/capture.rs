use std::time::Duration;

use serde_json::{Value, json};
use wiremock::matchers::{method, path, query_param, query_param_is_missing};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{CaptureOptions, Credentials, Error, XtreamClient, capture};

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

fn options() -> CaptureOptions {
    CaptureOptions {
        list_limit: 3,
        epg_channels: 2,
        pause: Duration::ZERO,
    }
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
        .expect(1)
        .mount(&server)
        .await;
    let streams: Vec<Value> = (1..=10)
        .map(|i| {
            json!({
                "stream_id": i.to_string(),
                "name": format!("Chaîne {i}"),
                "stream_icon": format!("{base}/logos/{i}.png"),
                "epg_channel_id": if i % 2 == 0 { json!(format!("ch{i}.fr")) } else { json!(null) },
                "direct_source": format!("{base}/live/{username}/{password}/{i}.ts"),
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
        "get_live_categories",
        ResponseTemplate::new(200)
            .set_body_json(json!({"0": {"category_id": 1}, "1": {"category_id": "2"}})),
    )
    .await;
    reply(
        &server,
        "get_vod_categories",
        ResponseTemplate::new(200).set_body_json(json!([])),
    )
    .await;
    reply(
        &server,
        "get_vod_streams",
        ResponseTemplate::new(200).set_body_string("<html>panne</html>"),
    )
    .await;
    reply(
        &server,
        "get_series_categories",
        ResponseTemplate::new(200).set_body_string(""),
    )
    .await;
    reply(&server, "get_series", ResponseTemplate::new(503)).await;
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .and(query_param("action", "get_short_epg"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "epg_listings": [{"title": "TGUgMjBo", "start_timestamp": "1", "stop_timestamp": 2}]
        })))
        .expect(2)
        .mount(&server)
        .await;
    server
}

#[tokio::test]
async fn capture_keeps_shapes_and_hides_the_account() {
    let server = panel(USER, PASS).await;
    let result = capture(&client(&server, USER, PASS), &options(), 1_700_000_000)
        .await
        .unwrap();
    assert_eq!(result.requests, 1 + 6 + 2);
    assert_eq!(result.failed, 1, "get_series en 503");

    let text = String::from_utf8(result.json.clone()).unwrap();
    for secret in [USER, PASS, "127.0.0.1", "203.0.113.7"] {
        assert!(!text.contains(secret), "{secret} présent dans la capture");
    }

    let file: Value = serde_json::from_slice(&result.json).unwrap();
    let responses = file["responses"].as_array().unwrap();
    let find = |action: &str| {
        responses
            .iter()
            .find(|r| r["action"] == action)
            .unwrap_or_else(|| panic!("{action} absent"))
    };

    let auth = &responses[0];
    assert_eq!(auth["action"], Value::Null);
    assert_eq!(auth["body"]["user_info"]["username"], "username");
    assert_eq!(auth["body"]["user_info"]["message"], "Bienvenue username");
    // Les types d'origine sont gardés (texte, nombre).
    assert_eq!(auth["body"]["user_info"]["exp_date"], "1893456000");
    assert_eq!(auth["body"]["user_info"]["auth"], 1);
    assert_eq!(auth["body"]["server_info"]["url"], "example.invalid");
    assert_eq!(auth["body"]["server_info"]["ip"], "192.0.2.1");

    let live = find("get_live_streams");
    assert_eq!(live["total"], 10);
    assert_eq!(live["kept"], 3);
    let kept = live["body"].as_array().unwrap();
    let ids: Vec<_> = kept
        .iter()
        .map(|s| s["stream_id"].as_str().unwrap())
        .collect();
    assert_eq!(
        ids,
        ["1", "4", "7"],
        "échantillon réparti sur toute la liste"
    );
    assert_eq!(kept[0]["stream_icon"], "http://example.invalid/logos/1.png");
    assert_eq!(
        kept[0]["direct_source"],
        "http://example.invalid/live/username/password/1.ts"
    );
    assert_eq!(kept[0]["epg_channel_id"], Value::Null);

    let categories = find("get_live_categories");
    assert_eq!(
        categories["body"],
        json!({"0": {"category_id": 1}, "1": {"category_id": "2"}})
    );
    assert_eq!(find("get_vod_categories")["body"], json!([]));
    assert_eq!(find("get_vod_streams")["text"], "<html>panne</html>");
    assert_eq!(find("get_series_categories")["body"], Value::Null);
    assert_eq!(find("get_series")["status"], 503);
    assert_eq!(find("get_series")["error"], "status");

    // Guide des chaînes qui en annoncent un, réparties sur la liste.
    let epg: Vec<_> = responses
        .iter()
        .filter(|r| r["action"] == "get_short_epg")
        .map(|r| r["params"]["stream_id"].as_str().unwrap())
        .collect();
    assert_eq!(epg, ["2", "6"]);
    assert_eq!(
        find("get_short_epg")["body"]["epg_listings"][0]["title"],
        "TGUgMjBo"
    );
}

#[tokio::test]
async fn short_credentials_are_hidden_in_urls() {
    let server = panel("ab", "xy").await;
    let result = capture(&client(&server, "ab", "xy"), &options(), 0)
        .await
        .unwrap();
    let file: Value = serde_json::from_slice(&result.json).unwrap();
    let live = file["responses"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["action"] == "get_live_streams")
        .unwrap();
    let first = &live["body"][0];
    assert_eq!(
        first["direct_source"],
        "http://example.invalid/live/username/password/1.ts"
    );
    assert_eq!(
        first["custom_sid"],
        "http://example.invalid/get.php?username=username&password=password"
    );
    // Trop courts pour être remplacés dans un texte : « Chaîne » reste intact.
    assert_eq!(first["name"], "Chaîne 1");
}

#[tokio::test]
async fn refused_credentials_stop_the_capture() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"user_info": {"auth": 0}})))
        .expect(1)
        .mount(&server)
        .await;
    let result = capture(&client(&server, USER, PASS), &options(), 0).await;
    assert!(matches!(result, Err(Error::Unauthorized)));
}

use serde_json::json;
use sha2::{Digest, Sha256};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{AppUpdate, Error, check_tester_update, check_update, download_update};

const REPO: &str = "owner/app";

fn hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

async fn releases(body: serde_json::Value) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/repos/owner/app/releases"))
        .respond_with(ResponseTemplate::new(200).set_body_json(body))
        .expect(1)
        .mount(&server)
        .await;
    server
}

fn apk(name: &str, url: &str) -> serde_json::Value {
    json!({"name": name, "browser_download_url": url, "size": 3,
           "digest": format!("sha256:{}", hex(b"apk").to_uppercase())})
}

#[tokio::test]
async fn newest_release_with_an_apk_is_offered() {
    let server = releases(json!([
        // Ordre quelconque, pre-releases comprises.
        {"tag_name": "v0.5.2", "prerelease": true, "body": "ancienne",
         "assets": [apk("hey-stream-0.5.2.apk", "https://x/0.5.2.apk")]},
        {"tag_name": "v0.10.0", "draft": true, "assets": [apk("a.apk", "https://x/draft.apk")]},
        {"tag_name": "v0.7.0", "body": "sans APK", "assets": [{"name": "notes.txt"}]},
        {"tag_name": "nightly", "assets": [apk("n.apk", "https://x/n.apk")]},
        {"tag_name": "v0.6.0", "prerelease": true, "body": "- Mise à jour intégrée",
         "assets": [{"name": "SHA256SUMS"}, apk("hey-stream-0.6.0.APK", "https://x/0.6.0.apk")]},
        "illisible"
    ]))
    .await;
    let update = check_update(&server.uri(), REPO, "0.5.2")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        update,
        AppUpdate {
            version: "0.6.0".into(),
            notes: "- Mise à jour intégrée".into(),
            apk_url: "https://x/0.6.0.apk".into(),
            size: 3,
            sha256: Some(hex(b"apk")),
            token: None,
        }
    );
}

#[tokio::test]
async fn a_tester_apk_on_github_is_never_offered() {
    let server = releases(json!([
        {"tag_name": "v0.7.0", "assets": [
            apk("hey-stream-0.7.0-testeur.apk", "https://x/0.7.0-testeur.apk"),
            apk("hey-stream-0.7.0.apk", "https://x/0.7.0.apk")]},
        {"tag_name": "v0.8.0", "assets": [apk("hey-stream-0.8.0-testeur.apk", "https://x/bad.apk")]}
    ]))
    .await;
    let update = check_update(&server.uri(), REPO, "0.6.0")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(update.apk_url, "https://x/0.7.0.apk");
}

#[tokio::test]
async fn tester_updates_come_from_the_collection_service_with_the_key() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/update"))
        .and(header("authorization", "Bearer cle"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "version": "0.7.0", "notes": "- Collecte", "size": 3, "sha256": hex(b"apk").to_uppercase()})))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/update/apk"))
        .and(header("authorization", "Bearer cle"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(&b"apk"[..]))
        .mount(&server)
        .await;
    let device = "0f8fad5b-d9cb-469f-a165-70867728950e";
    let update = check_tester_update(&server.uri(), "cle", "0.6.0", device)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(update.version, "0.7.0");
    assert_eq!(update.token.as_deref(), Some("cle"));
    let file = target("tester.apk");
    download_update(&update, &file, |_, _| {}).await.unwrap();
    assert_eq!(std::fs::read(&file).unwrap(), b"apk");
    assert_eq!(
        check_tester_update(&server.uri(), "cle", "0.7.0", device)
            .await
            .unwrap(),
        None
    );
    // Sans version publiée.
    let empty = MockServer::start().await;
    assert_eq!(
        check_tester_update(&empty.uri(), "cle", "0.6.0", device)
            .await
            .unwrap(),
        None
    );
}

#[tokio::test]
async fn nothing_newer_means_no_update() {
    let server = releases(json!([
        {"tag_name": "v0.6.0", "assets": [apk("a.apk", "https://x/a.apk")]}
    ]))
    .await;
    assert_eq!(
        check_update(&server.uri(), REPO, "0.6.0").await.unwrap(),
        None
    );
}

#[tokio::test]
async fn rate_limit_is_an_error() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!({"message": "rate limit"})))
        .mount(&server)
        .await;
    assert!(matches!(
        check_update(&server.uri(), REPO, "0.6.0").await,
        Err(Error::Unauthorized)
    ));
}

fn update(server: &MockServer, size: u64, sha256: Option<String>) -> AppUpdate {
    AppUpdate {
        version: "0.6.0".into(),
        notes: String::new(),
        apk_url: format!("{}/download/app.apk", server.uri()),
        size,
        sha256,
        token: None,
    }
}

async fn file_server(content: &'static [u8]) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/download/app.apk"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(content))
        .mount(&server)
        .await;
    server
}

fn target(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("hs-update-{name}-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    dir.join("app.apk")
}

#[tokio::test]
async fn download_is_verified() {
    let content: &'static [u8] = &[7u8; 100_000];
    let server = file_server(content).await;
    let path = target("ok");
    let mut last = (0, 0);
    download_update(
        &update(&server, content.len() as u64, Some(hex(content))),
        &path,
        |done, total| last = (done, total),
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&path).unwrap(), content);
    assert_eq!(last, (100_000, 100_000));
    std::fs::remove_dir_all(path.parent().unwrap()).unwrap();
}

#[tokio::test]
async fn corrupted_download_leaves_nothing() {
    let server = file_server(b"tampered").await;
    for (name, size, digest) in [("digest", 8, Some(hex(b"original"))), ("size", 9, None)] {
        let path = target(name);
        let result = download_update(&update(&server, size, digest), &path, |_, _| {}).await;
        assert!(matches!(result, Err(Error::InvalidResponse)), "{name}");
        let dir = path.parent().unwrap();
        assert_eq!(
            std::fs::read_dir(dir).unwrap().count(),
            0,
            "{name} : fichier laissé"
        );
        std::fs::remove_dir_all(dir).unwrap();
    }
}

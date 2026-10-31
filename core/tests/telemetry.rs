use std::io::Read;
use std::path::PathBuf;

use flate2::read::GzDecoder;
use serde_json::json;
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{TelemetryKind, TelemetryTarget, UploadOutcome, upload_telemetry};

fn temp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("heystream-telemetry-it-{name}"));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn target(server: &MockServer) -> TelemetryTarget {
    TelemetryTarget {
        endpoint: format!("{}/", server.uri()),
        key: "cle-envoi".into(),
        device: "0f8fad5b-d9cb-469f-a165-70867728950e".into(),
        app_version: "0.7.0".into(),
    }
}

async fn status(server: &MockServer, response: ResponseTemplate) {
    Mock::given(method("GET"))
        .and(path("/v1/status"))
        .and(header("authorization", "Bearer cle-envoi"))
        .and(header("x-device", "0f8fad5b-d9cb-469f-a165-70867728950e"))
        .respond_with(response)
        .mount(server)
        .await;
}

fn gunzip(body: &[u8]) -> String {
    let mut out = String::new();
    GzDecoder::new(body).read_to_string(&mut out).unwrap();
    out
}

#[tokio::test]
async fn files_are_sent_as_json_lines_then_deleted() {
    let server = MockServer::start().await;
    status(
        &server,
        ResponseTemplate::new(200).set_body_json(json!({"collect": true})),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/v1/ingest"))
        .and(header("x-app-version", "0.7.0"))
        .respond_with(ResponseTemplate::new(204))
        .expect(2)
        .mount(&server)
        .await;
    let responses = temp("sent-responses");
    let events = temp("sent-events");
    std::fs::write(responses.join("1-auth.json"), br#"{"action":null}"#).unwrap();
    std::fs::write(
        responses.join("2-get_live_streams.json"),
        br#"{"action":"get_live_streams"}"#,
    )
    .unwrap();
    std::fs::write(responses.join("3-auth.json.part"), b"{\"inco").unwrap();
    std::fs::write(
        events.join("1.jsonl"),
        b"{\"type\":\"screen\"}\n{\"type\":\"play\"}\n",
    )
    .unwrap();
    std::fs::write(events.join("2.open"), b"{\"type\":\"en cours\"}\n").unwrap();

    let outcome = upload_telemetry(
        &target(&server),
        &[
            (TelemetryKind::Responses, responses.clone()),
            (TelemetryKind::Events, events.clone()),
        ],
    )
    .await
    .unwrap();
    assert!(matches!(outcome, UploadOutcome::Sent { files: 3, .. }));

    let requests = server.received_requests().await.unwrap();
    let posts: Vec<_> = requests
        .iter()
        .filter(|r| r.method.as_str() == "POST")
        .collect();
    assert_eq!(posts[0].headers["x-kind"], "responses");
    assert_eq!(
        gunzip(&posts[0].body),
        "{\"action\":null}\n{\"action\":\"get_live_streams\"}\n"
    );
    assert_eq!(posts[1].headers["x-kind"], "events");
    assert_eq!(
        gunzip(&posts[1].body),
        "{\"type\":\"screen\"}\n{\"type\":\"play\"}\n"
    );
    // Envoyés : effacés ; en cours d'écriture : gardés.
    assert!(!responses.join("1-auth.json").exists());
    assert!(responses.join("3-auth.json.part").exists());
    assert!(!events.join("1.jsonl").exists());
    assert!(events.join("2.open").exists());
}

#[tokio::test]
async fn nothing_is_sent_while_collection_is_paused() {
    for response in [
        ResponseTemplate::new(200).set_body_json(json!({"collect": false})),
        ResponseTemplate::new(423),
    ] {
        let server = MockServer::start().await;
        status(&server, response).await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(204))
            .expect(0)
            .mount(&server)
            .await;
        let events = temp("paused");
        std::fs::write(events.join("1.jsonl"), b"{}\n").unwrap();
        let outcome =
            upload_telemetry(&target(&server), &[(TelemetryKind::Events, events.clone())])
                .await
                .unwrap();
        assert_eq!(outcome, UploadOutcome::Paused);
        assert!(events.join("1.jsonl").exists());
    }
}

#[tokio::test]
async fn a_refused_batch_stays_for_the_next_run() {
    let server = MockServer::start().await;
    status(
        &server,
        ResponseTemplate::new(200).set_body_json(json!({"collect": true})),
    )
    .await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    let events = temp("refused");
    std::fs::write(events.join("1.jsonl"), b"{}\n").unwrap();
    assert!(
        upload_telemetry(&target(&server), &[(TelemetryKind::Events, events.clone())])
            .await
            .is_err()
    );
    assert!(events.join("1.jsonl").exists());
}

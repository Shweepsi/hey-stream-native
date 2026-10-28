use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{
    Catalog, Credentials, EPG_MIN_INTERVAL_SECS, Programme, XtreamClient, now_next_for,
};

fn b64(text: &str) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(text)
}

fn client(server: &MockServer) -> XtreamClient {
    XtreamClient::new(&Credentials {
        server_url: server.uri(),
        username: "u".into(),
        password: "p".into(),
    })
    .unwrap()
}

async fn epg_server(listings: serde_json::Value, expected_calls: u64) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .and(query_param("action", "get_short_epg"))
        .and(query_param("stream_id", "7"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"epg_listings": listings})))
        .expect(expected_calls)
        .mount(&server)
        .await;
    server
}

#[tokio::test]
async fn short_epg_decodes_and_sorts() {
    let server = epg_server(
        json!([
            {"title": b64("Le 20h"), "description": b64("Journal télévisé"),
             "start_timestamp": "2000", "stop_timestamp": "2600"},
            {"title": b64("Météo"), "description": "", "start_timestamp": 1900, "stop_timestamp": 2000},
            {"title": "Titre en clair !", "start_timestamp": "2600", "stop_timestamp": "5000"},
            {"title": b64("Sans horaires")}
        ]),
        1,
    )
    .await;
    let programmes = client(&server).short_epg(7).await.unwrap();
    let titles: Vec<_> = programmes.iter().map(|p| p.title.as_str()).collect();
    assert_eq!(titles, ["Météo", "Le 20h", "Titre en clair !"]);
    assert_eq!(
        programmes[1].description.as_deref(),
        Some("Journal télévisé")
    );
    assert_eq!(programmes[0].description, None);
}

#[tokio::test]
async fn now_next_is_cached_and_rate_limited() {
    let listings = json!([
        {"title": b64("Le 20h"), "start_timestamp": 2000, "stop_timestamp": 2600},
        {"title": b64("Film"), "start_timestamp": 2600, "stop_timestamp": 9000}
    ]);
    // Un seul appel malgré trois consultations.
    let server = epg_server(listings, 1).await;
    let client = client(&server);
    let mut catalog = Catalog::open_in_memory().unwrap();

    let nn = now_next_for(&client, &mut catalog, 7, 2100).await.unwrap();
    assert_eq!(nn.now.unwrap().title, "Le 20h");
    assert_eq!(nn.next.unwrap().title, "Film");

    let nn = now_next_for(&client, &mut catalog, 7, 2200).await.unwrap();
    assert_eq!(nn.now.unwrap().title, "Le 20h");

    // Le film a commencé : plus de « suivant » connu, mais moins de 15 min
    // depuis la dernière demande, donc pas de nouvel appel.
    let nn = now_next_for(&client, &mut catalog, 7, 2700).await.unwrap();
    assert_eq!(nn.now.unwrap().title, "Film");
    assert!(nn.next.is_none());
}

#[test]
fn refetch_only_when_next_unknown_and_interval_elapsed() {
    let mut catalog = Catalog::open_in_memory().unwrap();
    assert!(catalog.epg_needs_fetch(7, 0).unwrap());
    let programme = |title: &str, start, stop| Programme {
        title: title.into(),
        description: None,
        start,
        stop,
    };
    catalog
        .store_epg(
            7,
            &[programme("A", 1000, 2000), programme("B", 2000, 6000)],
            1200,
        )
        .unwrap();
    assert!(!catalog.epg_needs_fetch(7, 1300).unwrap());
    // B a commencé, suivant inconnu, mais demande récente (< 15 min) : on attend.
    assert!(!catalog.epg_needs_fetch(7, 2050).unwrap());
    assert!(
        catalog
            .epg_needs_fetch(7, 1200 + EPG_MIN_INTERVAL_SECS)
            .unwrap()
    );
    // Les programmes terminés sont effacés au prochain enregistrement.
    catalog
        .store_epg(7, &[programme("C", 5000, 9000)], 6000)
        .unwrap();
    assert_eq!(catalog.now_next(7, 6000).unwrap().now.unwrap().title, "C");
}

#[tokio::test]
async fn network_error_keeps_cached_programmes() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .respond_with(ResponseTemplate::new(503))
        .expect(1)
        .mount(&server)
        .await;
    let mut catalog = Catalog::open_in_memory().unwrap();
    let a = Programme {
        title: "A".into(),
        description: None,
        start: 0,
        stop: 10_000,
    };
    catalog.store_epg(7, &[a], 0).unwrap();

    // Suivant inconnu et cache ancien : on redemande, le serveur échoue.
    let nn = now_next_for(&client(&server), &mut catalog, 7, 5_000)
        .await
        .unwrap();
    assert_eq!(nn.now.unwrap().title, "A");
    // Pas de nouvel essai juste après l'échec.
    assert!(!catalog.epg_needs_fetch(7, 5_100).unwrap());
}

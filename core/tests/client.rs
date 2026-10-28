use serde_json::json;
use wiremock::matchers::{method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{Credentials, Error, StreamKind, XtreamClient, normalize_server_url};

fn client(server: &MockServer) -> XtreamClient {
    XtreamClient::new(&Credentials {
        server_url: format!("{}/", server.uri()),
        username: " alice ".into(),
        password: "s3cret".into(),
    })
    .unwrap()
}

async fn mock(server: &MockServer, action: Option<&str>, body: ResponseTemplate) {
    let mut m = Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .and(query_param("username", "alice"))
        .and(query_param("password", "s3cret"));
    if let Some(action) = action {
        m = m.and(query_param("action", action));
    }
    m.respond_with(body).mount(server).await;
}

#[tokio::test]
async fn authenticate_reads_string_numbers() {
    let server = MockServer::start().await;
    let body = json!({
        "user_info": {"auth": 1, "status": "Active", "exp_date": "1893456000",
                      "max_connections": "2", "active_cons": 0},
        "server_info": {"url": "example.com", "port": 8080, "timezone": "Europe/Paris"}
    });
    mock(
        &server,
        None,
        ResponseTemplate::new(200).set_body_json(body),
    )
    .await;

    let account = client(&server).authenticate().await.unwrap();
    assert_eq!(account.expires_at, Some(1_893_456_000));
    assert_eq!(account.max_connections, Some(2));
    assert_eq!(account.server.port.as_deref(), Some("8080"));
}

#[tokio::test]
async fn authenticate_rejects_auth_zero_and_expired() {
    let server = MockServer::start().await;
    mock(
        &server,
        None,
        ResponseTemplate::new(200).set_body_json(json!({"user_info": {"auth": 0}})),
    )
    .await;
    assert!(matches!(
        client(&server).authenticate().await,
        Err(Error::Unauthorized)
    ));

    let server = MockServer::start().await;
    let body = json!({"user_info": {"auth": "1", "status": "Expired"}});
    mock(
        &server,
        None,
        ResponseTemplate::new(200).set_body_json(body),
    )
    .await;
    assert!(matches!(
        client(&server).authenticate().await,
        Err(Error::AccountInactive)
    ));
}

#[tokio::test]
async fn lists_tolerate_null_empty_and_broken_items() {
    let server = MockServer::start().await;
    mock(
        &server,
        Some("get_live_categories"),
        ResponseTemplate::new(200).set_body_string("null"),
    )
    .await;
    mock(
        &server,
        Some("get_vod_categories"),
        ResponseTemplate::new(200).set_body_string(""),
    )
    .await;
    let streams = json!([
        {"stream_id": "12", "name": "TF1", "tv_archive": "1", "num": 1},
        {"name": "sans identifiant"},
        {"stream_id": 13, "name": "France 2", "category_id": 4}
    ]);
    mock(
        &server,
        Some("get_live_streams"),
        ResponseTemplate::new(200).set_body_json(streams),
    )
    .await;

    let c = client(&server);
    assert!(c.live_categories().await.unwrap().is_empty());
    assert!(c.movie_categories().await.unwrap().is_empty());
    let live = c.live_streams(None).await.unwrap();
    assert_eq!(live.len(), 2);
    assert_eq!(live[0].stream_id, 12);
    assert!(live[0].tv_archive);
    assert_eq!(live[1].category_id.as_deref(), Some("4"));
}

#[tokio::test]
async fn list_with_refused_credentials_is_unauthorized() {
    let server = MockServer::start().await;
    mock(
        &server,
        Some("get_series"),
        ResponseTemplate::new(200).set_body_json(json!({"user_info": {"auth": 0}})),
    )
    .await;
    assert!(matches!(
        client(&server).series(None).await,
        Err(Error::Unauthorized)
    ));
}

#[tokio::test]
async fn http_errors_are_typed() {
    let server = MockServer::start().await;
    mock(
        &server,
        Some("get_series_categories"),
        ResponseTemplate::new(503),
    )
    .await;
    assert!(matches!(
        client(&server).series_categories().await,
        Err(Error::Server(503))
    ));
}

#[tokio::test]
async fn stream_urls() {
    let server = MockServer::start().await;
    let c = client(&server);
    assert_eq!(
        c.stream_url(StreamKind::Live, 12, None).as_str(),
        format!("{}/live/alice/s3cret/12.ts", server.uri())
    );
    assert_eq!(
        c.stream_url(StreamKind::Movie, 7, Some("mkv")).as_str(),
        format!("{}/movie/alice/s3cret/7.mkv", server.uri())
    );
}

#[test]
fn server_url_normalization() {
    assert_eq!(
        normalize_server_url(" panel.example:8080/ ")
            .unwrap()
            .as_str(),
        "http://panel.example:8080/"
    );
    assert_eq!(
        normalize_server_url("https://p.example").unwrap().scheme(),
        "https"
    );
    assert!(normalize_server_url("").is_err());
    assert!(normalize_server_url("ftp://p.example").is_err());
}

#[test]
fn empty_credentials_are_rejected() {
    let err = XtreamClient::new(&Credentials {
        server_url: "p.example".into(),
        username: " ".into(),
        password: "x".into(),
    });
    assert!(matches!(err, Err(Error::MissingCredentials)));
}

#[tokio::test]
async fn refresh_retries_empty_categories() {
    use xtream_core::{Catalog, RefreshOutcome, Section, refresh_section};

    let server = MockServer::start().await;
    let base = || {
        Mock::given(method("GET"))
            .and(path("/player_api.php"))
            .and(query_param("action", "get_vod_categories"))
    };
    // Le panneau de référence renvoie `[]` par intermittence.
    base()
        .respond_with(ResponseTemplate::new(200).set_body_string("[]"))
        .up_to_n_times(2)
        .with_priority(1)
        .mount(&server)
        .await;
    base()
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!([{"category_id": "1", "category_name": "Action"}])),
        )
        .with_priority(2)
        .mount(&server)
        .await;
    mock(
        &server,
        Some("get_vod_streams"),
        ResponseTemplate::new(200)
            .set_body_json(json!([{"stream_id": 1, "name": "A", "category_id": "1"}])),
    )
    .await;

    let mut catalog = Catalog::open_in_memory().unwrap();
    let outcome = refresh_section(&client(&server), &mut catalog, Section::Movies, 100)
        .await
        .unwrap();
    assert_eq!(
        outcome,
        RefreshOutcome::Updated {
            categories: 1,
            items: 1
        }
    );
    assert_eq!(
        catalog.categories(Section::Movies).unwrap()[0].name,
        "Action"
    );
}

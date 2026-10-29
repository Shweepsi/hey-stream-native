use serde_json::json;
use wiremock::matchers::{header, method, path, query_param};
use wiremock::{Mock, MockServer, ResponseTemplate};
use xtream_core::{Credentials, USER_AGENT, XtreamClient};

fn client(server: &MockServer) -> XtreamClient {
    XtreamClient::new(&Credentials {
        server_url: server.uri(),
        username: "u".into(),
        password: "p".into(),
    })
    .unwrap()
}

async fn serve(action: &str, id_param: (&str, &str), body: serde_json::Value) -> MockServer {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/player_api.php"))
        .and(query_param("action", action))
        .and(query_param(id_param.0, id_param.1))
        .respond_with(ResponseTemplate::new(200).set_body_json(body))
        .mount(&server)
        .await;
    server
}

#[tokio::test]
async fn sends_honest_user_agent() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(header("user-agent", USER_AGENT))
        .respond_with(ResponseTemplate::new(200).set_body_string("[]"))
        .expect(1)
        .mount(&server)
        .await;
    client(&server).live_categories().await.unwrap();
    assert!(USER_AGENT.starts_with("HeyStream/"));
}

#[tokio::test]
async fn movie_info_reads_year_duration_and_extension() {
    let server = serve(
        "get_vod_info",
        ("vod_id", "42"),
        json!({
            "info": {"plot": "Une jeune serveuse…", "releasedate": "2001-04-25",
                     "duration": "02:02:00", "rating": "7.9", "movie_image": "http://img/a.jpg"},
            "movie_data": {"stream_id": 42, "name": "Amélie", "container_extension": "mkv"}
        }),
    )
    .await;
    let info = client(&server).movie_info(42).await.unwrap();
    assert_eq!(info.name.as_deref(), Some("Amélie"));
    assert_eq!(info.year.as_deref(), Some("2001"));
    assert_eq!(info.duration_secs, Some(7320));
    assert_eq!(info.rating.as_deref(), Some("7.9"));
    assert_eq!(info.extension.as_deref(), Some("mkv"));
}

#[tokio::test]
async fn movie_info_reads_a_bare_duration_as_minutes() {
    let server = serve(
        "get_vod_info",
        ("vod_id", "7"),
        json!({"info": {"duration": "109"}, "movie_data": {"name": "X", "container_extension": "mkv"}}),
    )
    .await;
    let info = client(&server).movie_info(7).await.unwrap();
    assert_eq!(info.duration_secs, Some(109 * 60));
}

#[tokio::test]
async fn movie_info_reads_a_large_bare_duration_as_seconds() {
    let server = serve(
        "get_vod_info",
        ("vod_id", "8"),
        json!({"info": {"duration": "6540"}, "movie_data": {"name": "X", "container_extension": "mkv"}}),
    )
    .await;
    let info = client(&server).movie_info(8).await.unwrap();
    assert_eq!(info.duration_secs, Some(6540));
}

#[tokio::test]
async fn movie_info_tolerates_empty_info() {
    let server = serve(
        "get_vod_info",
        ("vod_id", "1"),
        json!({"info": [], "movie_data": {"name": "X", "container_extension": "mp4"}}),
    )
    .await;
    let info = client(&server).movie_info(1).await.unwrap();
    assert_eq!(info.name.as_deref(), Some("X"));
    assert_eq!(info.plot, None);
    assert_eq!(info.duration_secs, None);
}

#[tokio::test]
async fn series_info_groups_sorts_and_finds_next_episode() {
    let server = serve(
        "get_series_info",
        ("series_id", "7"),
        json!({
            "info": {"name": "Lupin", "plot": "Assane Diop…", "releaseDate": "2021-01-08", "rating": 8},
            "seasons": [{"season_number": 2, "name": "Partie 2"}, {"season_number": 1, "name": "Partie 1"}],
            "episodes": {
                "2": [{"id": "201", "episode_num": 1, "title": "S2E1", "container_extension": "mkv",
                       "info": {"duration_secs": 2700, "plot": "Suite"}}],
                "1": [
                    {"id": "102", "episode_num": "2", "title": "S1E2", "info": []},
                    {"id": "101", "episode_num": 1, "title": "S1E1", "info": {"duration": "45:00"}},
                    {"title": "sans identifiant"}
                ]
            }
        }),
    )
    .await;
    let info = client(&server).series_info(7).await.unwrap();
    assert_eq!(info.name.as_deref(), Some("Lupin"));
    assert_eq!(info.year.as_deref(), Some("2021"));
    assert_eq!(info.rating.as_deref(), Some("8"));

    let seasons: Vec<_> = info
        .seasons
        .iter()
        .map(|s| (s.number, s.name.as_deref(), s.episodes.len()))
        .collect();
    assert_eq!(
        seasons,
        [(1, Some("Partie 1"), 2), (2, Some("Partie 2"), 1)]
    );
    let s1: Vec<_> = info.seasons[0].episodes.iter().map(|e| e.id).collect();
    assert_eq!(s1, [101, 102]);
    assert_eq!(info.seasons[0].episodes[0].duration_secs, Some(2700));
    assert_eq!(
        info.seasons[1].episodes[0].extension.as_deref(),
        Some("mkv")
    );

    assert_eq!(info.next_episode(101).unwrap().id, 102);
    // Fin de saison : premier épisode de la suivante.
    assert_eq!(info.next_episode(102).unwrap().id, 201);
    assert!(info.next_episode(201).is_none());
    assert!(info.next_episode(999).is_none());
}

#[tokio::test]
async fn series_info_accepts_episode_arrays() {
    let server = serve(
        "get_series_info",
        ("series_id", "8"),
        json!({
            "info": null,
            "episodes": [[{"id": 5, "season": 3, "episode_num": 1}], [{"id": 6, "season": 3, "episode_num": 2}]]
        }),
    )
    .await;
    let info = client(&server).series_info(8).await.unwrap();
    assert_eq!(info.seasons.len(), 1);
    assert_eq!(info.seasons[0].number, 3);
    assert_eq!(info.seasons[0].episodes.len(), 2);
}

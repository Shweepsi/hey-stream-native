//! Vérification contre un vrai panneau, identifiants lus dans l'environnement
//! (jamais dans le dépôt) :
//!
//! ```sh
//! XTREAM_URL=… XTREAM_USER=… XTREAM_PASS=… cargo run --example smoke
//! ```

use std::time::Instant;

use xtream_core::{Catalog, Credentials, Section, XtreamClient, refresh_section};

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let env = |key: &str| std::env::var(key).map_err(|_| format!("variable {key} manquante"));
    let client = XtreamClient::new(&Credentials {
        server_url: env("XTREAM_URL")?,
        username: env("XTREAM_USER")?,
        password: env("XTREAM_PASS")?,
    })?;

    let t = Instant::now();
    let account = client.authenticate().await?;
    println!(
        "auth OK en {:?} — expiration {:?}, connexions max {:?}",
        t.elapsed(),
        account.expires_at,
        account.max_connections
    );

    let t = Instant::now();
    let (lc, live) = (
        client.live_categories().await?,
        client.live_streams(None).await?,
    );
    println!(
        "direct : {} catégories, {} chaînes en {:?}",
        lc.len(),
        live.len(),
        t.elapsed()
    );
    let t = Instant::now();
    let (mc, movies) = (client.movie_categories().await?, client.movies(None).await?);
    println!(
        "films  : {} catégories, {} films en {:?}",
        mc.len(),
        movies.len(),
        t.elapsed()
    );
    let t = Instant::now();
    let (sc, series) = (
        client.series_categories().await?,
        client.series(None).await?,
    );
    println!(
        "séries : {} catégories, {} séries en {:?}",
        sc.len(),
        series.len(),
        t.elapsed()
    );
    let path = std::env::temp_dir().join("hey-stream-smoke.db");
    let _ = std::fs::remove_file(&path);
    let mut catalog = Catalog::open(&path)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs() as i64;
    for section in Section::ALL {
        let t = Instant::now();
        let outcome = refresh_section(&client, &mut catalog, section, now).await?;
        println!("cache {section:?} : {outcome:?} en {:?}", t.elapsed());
    }
    let t = Instant::now();
    let first = catalog.categories(Section::Movies)?;
    let page = catalog.items(Section::Movies, first.first().map(|c| c.id.as_str()), 0, 60)?;
    println!(
        "lecture : {} catégories + page de {} films en {:?}",
        first.len(),
        page.len(),
        t.elapsed()
    );
    let t = Instant::now();
    let hits = catalog.search(Section::Movies, "the", 50)?;
    println!(
        "recherche « the » : {} résultats en {:?}",
        hits.len(),
        t.elapsed()
    );
    println!(
        "taille du cache : {} Ko",
        std::fs::metadata(&path)?.len() / 1024
    );
    Ok(())
}

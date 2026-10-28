//! Vérification contre un vrai panneau, identifiants lus dans l'environnement
//! (jamais dans le dépôt) :
//!
//! ```sh
//! XTREAM_URL=… XTREAM_USER=… XTREAM_PASS=… cargo run --example smoke
//! ```

use std::time::Instant;

use xtream_core::{Credentials, XtreamClient};

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
    Ok(())
}

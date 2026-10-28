//! Vérification contre un vrai panneau, identifiants lus dans l'environnement
//! (jamais dans le dépôt). À lancer à la main, depuis son propre réseau
//! uniquement : jamais en CI ni depuis un serveur (risque de bannissement du
//! compte par le fournisseur).
//!
//! Requêtes par exécution : 1 authentification + 6 listes (catalogue
//! téléchargé une seule fois), plus 5 au maximum par liste de catégories
//! renvoyée vide.
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

    let path = std::env::temp_dir().join("hey-stream-smoke.db");
    let _ = std::fs::remove_file(&path);
    let mut catalog = Catalog::open(&path)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs() as i64;
    for section in Section::ALL {
        let t = Instant::now();
        let outcome = refresh_section(&client, &mut catalog, section, now).await?;
        println!(
            "{section:?} : {outcome:?}, {} catégories, en {:?}",
            catalog.categories(section)?.len(),
            t.elapsed()
        );
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

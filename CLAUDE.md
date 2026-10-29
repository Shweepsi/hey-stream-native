# CLAUDE.md — hey-stream (natif)

Lire d’abord `README.md`, `docs/SPEC_V1.md` (périmètre v1 : rien d’autre), `docs/DECISIONS.md`, `docs/MATERIEL.md` et, avant toute interface, `docs/DESIGN.md`.

## Règles

- **Aucun code, image ou texte issu d'IPTV Smarters** (ni d'aucune app
  propriétaire). Pas de décompilation. On reproduit des qualités (simplicité,
  stabilité), pas une apparence.
- Toute la logique va dans `core/` (Rust) ; les interfaces natives ne font
  qu'afficher.
- Le cœur ne produit **aucun texte affiché** : il renvoie des types d'erreur
  que chaque interface traduit.
- Le cœur est **tolérant** : une entrée illisible d'un panneau est ignorée,
  jamais fatale pour toute une liste.
- Chaque fonctionnalité est testée sur l'appareil de référence (Mi Box S 2e gén.,
  32 bits, 2 Go) — voir `docs/MATERIEL.md`.
- Ne jamais journaliser identifiants ni URL de flux en clair.
- **Ne jamais appeler le panneau de référence depuis une session cloud, la CI
  ou un script automatique** : le fournisseur peut bannir un compte pour des
  requêtes répétées depuis une IP de datacenter. Les tests utilisent des
  réponses simulées (wiremock). `examples/smoke.rs` ne se lance qu'à la main,
  par le propriétaire, depuis son propre réseau.

- **Version** : toute PR qui change l'app monte `[workspace.package] version`
  (`Cargo.toml` racine) et ajoute son entrée en tête de `CHANGELOG.md`
  (`docs/DECISIONS.md`, « Versions »).

## Vérifications avant de livrer

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

App Android (voir `android/README.md`), depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

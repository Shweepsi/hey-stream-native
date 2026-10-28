# CLAUDE.md — hey-stream (natif)

Lire d'abord `README.md`, `docs/DECISIONS.md` et `docs/MATERIEL.md`.

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

## Vérifications avant de livrer

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

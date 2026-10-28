# hey-stream (natif)

Client IPTV **Xtream Codes** libre et gratuit, pour **téléphone, tablette et TV**,
sur **Android** et **Apple**. Sans publicité, sans compte, sans serveur : vos
identifiants restent sur votre appareil.

> hey-stream ne fournit aucun contenu. L'app lit uniquement les sources que
> l'utilisateur ajoute lui-même avec son propre abonnement.

## Architecture

```
core/      Rust — API Xtream, cache, recherche, contrôle parental, logique de lecture
android/   Kotlin + Jetpack Compose — téléphone, tablette, TV (un seul APK)   [à venir]
apple/     SwiftUI — iPhone, iPad, Apple TV                                    [à venir]
```

Le cœur Rust est exposé aux interfaces par [UniFFI](https://mozilla.github.io/uniffi-rs/).
Les interfaces ne font qu'afficher ; toute la logique est testée une seule fois, en Rust.

## État

| Partie | État |
|---|---|
| `core` : client Xtream tolérant (auth, catégories, direct, films, séries, URL de lecture) | ✅ testé |
| `core` : cache SQLite du catalogue, recherche, détection adulte | ✅ testé |
| `core` : favoris, récents, reprise de lecture | ✅ testé |
| `core` : contrôle parental (PIN), catégories masquées, EPG court | ✅ testé |
| `core` : User-Agent `HeyStream`, fiches film et série (saisons, épisodes) | ✅ testé |
| Liaisons UniFFI (Kotlin, Swift) : `ffi/`, `scripts/bindings.sh` | ✅ testé |
| Android (Compose + Compose for TV + Media3) | à faire |
| Apple (SwiftUI + AVPlayer) | à faire |

## Développer

```sh
cargo test
cargo clippy --all-targets -- -D warnings
scripts/bindings.sh          # liaisons Kotlin et Swift dans bindings/
```

Les interfaces n'utilisent que `ffi/` (classe `HeyStreamCore`) : ses méthodes
sont bloquantes et s'appellent hors du fil principal.

Voir [`docs/SPEC_V1.md`](docs/SPEC_V1.md), [`docs/DECISIONS.md`](docs/DECISIONS.md) et [`docs/MATERIEL.md`](docs/MATERIEL.md).

## Licence

GPL-3.0. Voir [`LICENSE`](LICENSE).

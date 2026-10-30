#!/usr/bin/env bash
# Régénère la liste des licences des crates Rust livrées dans l'app
# (écran Réglages › À propos › Licences tierces). Demande cargo-about :
#   cargo install cargo-about --locked --features cli
# La CI vérifie que le fichier est à jour.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo about generate --offline --fail -m ffi/Cargo.toml scripts/third_party.hbs \
  -o android/app/src/main/assets/licenses/rust.txt

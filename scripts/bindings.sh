#!/usr/bin/env bash
# Génère les liaisons Kotlin et Swift du cœur dans bindings/ (non versionné).
# Usage : scripts/bindings.sh [kotlin|swift|all]
set -euo pipefail
cd "$(dirname "$0")/.."
lang="${1:-all}"
cargo build -p heystream-ffi
lib="target/debug/libheystream.so"
[[ "$(uname)" == "Darwin" ]] && lib="target/debug/libheystream.dylib"
for l in kotlin swift; do
  if [[ "$lang" == "all" || "$lang" == "$l" ]]; then
    cargo run -q -p heystream-ffi --bin uniffi-bindgen -- generate \
      --library "$lib" --language "$l" \
      --out-dir "bindings/$l" --no-format
  fi
done
echo "Liaisons générées dans bindings/"

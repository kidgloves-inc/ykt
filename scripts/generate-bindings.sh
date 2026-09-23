#!/usr/bin/env bash
# The Kotlin bindings, from the UDL of the yniffi checkout Cargo resolved for
# this crate's pin — the same UDL the Swift bindings come from, read from
# the same commit Cargo.lock names, so there is no copy of it here to drift.
# uniffi.toml names the library the bindings load ("ykt", this crate's
# cdylib, which re-exports yniffi's symbols).
set -euo pipefail
cd "$(dirname "$0")/.."
UDL="$(cargo metadata --format-version 1 --locked \
    | grep -o '"manifest_path":"[^"]*/yswift-[^"]*/lib/Cargo.toml"' \
    | head -1 | sed 's/.*:"//; s#Cargo.toml"$#src/yniffi.udl#')"
[ -f "$UDL" ] || { echo "generate-bindings: no yniffi checkout under cargo's git checkouts — run cargo fetch" >&2; exit 1; }
cargo run --locked --features uniffi/cli --bin uniffi-bindgen -- generate "$UDL" \
    --language kotlin --config uniffi.toml --out-dir "${1:-build/generated/uniffi}"

#!/usr/bin/env bash
# Usa la TV como pantalla extra por wifi. Ctrl+C para apagarla.
set -euo pipefail
cd "$(dirname "$0")"
exec python3 -I pantalla_tv.py "$@"

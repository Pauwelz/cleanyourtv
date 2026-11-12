#!/usr/bin/env bash
# Send servers.json (the addresses and API keys of Sonarr and Radarr) to Releases on the TV, then open it.
# Build and install the add-on first (README.md). The keys travel as a file, never on a command line.
#
# Usage: TV_HOST=192.168.1.50 addons/releases/setup.sh                # servers.json from this folder
#        TV_HOST=192.168.1.50 addons/releases/setup.sh ~/servers.json # any file
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FILE="${1:-$HERE/servers.json}"
[ -f "$FILE" ] || { echo "no file $FILE (copy servers.example.json to servers.json and edit it)" >&2; exit 1; }
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY="$HERE/../../tools/venv/bin/python"
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
"$PY" -I -c 'import json, sys; json.load(open(sys.argv[1]))' "$FILE" || { echo "$FILE is not valid JSON" >&2; exit 1; }
PKG=dev.mpauwels.releases
STAGE=/data/local/tmp/releases-servers.json
tv() { "$PY" -I "$HERE/../../tools/tv.py" "$@"; }

tv "pm path $PKG; true" | grep -q package: || { echo "Releases is not on the TV: build and install it first (README.md)" >&2; exit 1; }
tv --push "$FILE" "$STAGE" >/dev/null
tv "content write --uri content://$PKG.config/servers.json < $STAGE; rm -f $STAGE; am start -n $PKG/.MainActivity >/dev/null; echo 'Sent. Releases is open on the TV.'"

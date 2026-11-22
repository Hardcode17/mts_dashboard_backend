#!/bin/sh
set -eu
[ "$#" -eq 0 ] || { echo 'Использование: ./send-pi.command' >&2; exit 2; }
exec python3 "$(dirname "$0")/operate.py" send

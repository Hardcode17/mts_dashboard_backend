#!/bin/sh
set -eu
case "$#:${1:-}" in
  0:) operation=reset ;;
  1:--check) operation=check ;;
  *) echo 'Использование: ./reset-pi.command [--check]' >&2; exit 2 ;;
esac
exec python3 "$(dirname "$0")/operate.py" "$operation"

#!/bin/sh
set -eu
cd "$(dirname "$0")"
proyecto="ticketya-test-$(date +%s)-$$"
cleanup() {
  docker compose -p "$proyecto" -f compose.test.yaml down --volumes --remove-orphans
  docker image rm "$proyecto-ms2-a" "$proyecto-ms2-b" "$proyecto-ms7-a" "$proyecto-ms7-b" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
docker compose -p "$proyecto" -f compose.test.yaml up --build --abort-on-container-exit --exit-code-from tests

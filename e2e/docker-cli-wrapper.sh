#!/bin/sh
set -eu

AUDIT_FILE=/tmp/autostopper-e2e-docker-commands.log
FAILURE_MARKER=/tmp/autostopper-e2e-failed-stop-once

printf '%s\n' "$*" >> "$AUDIT_FILE"

if [ "${AUTOSTOPPER_E2E_FAIL_STOP_ONCE:-false}" = "true" ] \
    && [ "${1:-}" = "stop" ] \
    && [ "${2:-}" = "${AUTOSTOPPER_E2E_BACKEND_CONTAINER:-}" ] \
    && [ ! -e "$FAILURE_MARKER" ]; then
  : > "$FAILURE_MARKER"
  echo "injected release-candidate stop failure" >&2
  exit 1
fi

exec /usr/bin/docker "$@"

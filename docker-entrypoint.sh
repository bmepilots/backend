#!/bin/sh
set -eu

if [ -z "${DB_PASSWORD:-}" ] && [ -n "${DB_PASSWORD_FILE:-}" ]; then
  DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"
  export DB_PASSWORD
fi

if [ -z "${BOOTSTRAP_ADMIN_PASSWORD:-}" ] && [ -n "${BOOTSTRAP_ADMIN_PASSWORD_FILE:-}" ]; then
  BOOTSTRAP_ADMIN_PASSWORD="$(cat "$BOOTSTRAP_ADMIN_PASSWORD_FILE")"
  export BOOTSTRAP_ADMIN_PASSWORD
fi

exec java ${JAVA_OPTS:-} -jar /app/portal.jar

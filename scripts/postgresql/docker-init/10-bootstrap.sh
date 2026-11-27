#!/bin/sh
# First-boot bootstrap: create iris_migrator / iris_app and the diagnosis/rag
# schemas. Runs inside the official postgres image as the superuser, only when
# the data volume is empty. Idempotent; safe to re-run by hand.
set -eu
psql -v ON_ERROR_STOP=1 \
     -v migrator_password="${IRIS_MIGRATOR_PASSWORD:?IRIS_MIGRATOR_PASSWORD is required}" \
     -v app_password="${IRIS_APP_PASSWORD:?IRIS_APP_PASSWORD is required}" \
     -d "${POSTGRES_DB:-iris}" \
     -f /opt/iris/bootstrap-roles.sql

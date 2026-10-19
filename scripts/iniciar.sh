#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export SPRING_PROFILES_ACTIVE="${1:-demo}"
if [[ -z "${REDENT_ADMIN_PASSWORD:-}" ]]; then
 read -rs -p 'Clave inicial admin (12 caracteres; vacia si ya existe): ' REDENT_ADMIN_PASSWORD
 echo
 export REDENT_ADMIN_PASSWORD
fi
exec java -Xms128m -Xmx768m -jar redent-local.jar

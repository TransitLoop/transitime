#!/usr/bin/env bash
# Start PostgreSQL schema, import GTFS, then run TransitClock Core and Tomcat.
# Scania client id and secret come from the environment and override the config file.
set -euo pipefail

TRANSITCLOCK_HOME=/usr/local/transitclock
CATALINA_HOME="${CATALINA_HOME:-/usr/local/tomcat}"
CONFIG_FILES="${TRANSITCLOCK_CONFIG_FILES:-/etc/transitclock/transitclock.properties}"
HIBERNATE="${TRANSITCLOCK_HOME}/config/hibernate.cfg.xml"
CP="${TRANSITCLOCK_HOME}/Core.jar"
GTFS_ZIP="${GTFS_ZIP_PATH:-/data/gtfs/gtfs.zip}"
CORE_XMX="${CORE_XMX:-1536m}"
USER_TIMEZONE="${USER_TIMEZONE:-Africa/Casablanca}"
MAX_TRAVEL_TIME_SEGMENT_LENGTH="${MAX_TRAVEL_TIME_SEGMENT_LENGTH:-100}"
API_KEY_NAME="${API_KEY_NAME:-microk8s}"
API_KEY_URL="${API_KEY_URL:-http://transitime.mdinamapper.svc.cluster.local:8080}"
API_KEY_EMAIL="${API_KEY_EMAIL:-ops@transitloop.local}"
API_KEY_PHONE="${API_KEY_PHONE:-0}"
API_KEY_DESCRIPTION="${API_KEY_DESCRIPTION:-TransitClock on MicroK8s}"
LOG_DIR="${TRANSITCLOCK_HOME}/logs"

export PGPASSWORD
export PGSSLMODE="${PGSSLMODE:-prefer}"

log() {
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    log "Missing required environment variable ${name}"
    exit 1
  fi
}

psql_exec() {
  psql -v ON_ERROR_STOP=1 \
    -h "${DB_HOST}" \
    -p "${DB_PORT}" \
    -U "${DB_USERNAME}" \
    -d "${DB_NAME}" \
    "$@"
}

load_config() {
  python3 - "${CONFIG_FILES}" <<'PY'
import shlex
import sys

paths = [path for path in sys.argv[1].split(";") if path]
props = {}
for path in paths:
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("!") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            if key not in props:
                props[key] = value.strip()

def need(key):
    value = props.get(key, "")
    if not value:
        raise SystemExit(f"Config file is missing {key}")
    return value

hostport = need("transitclock.db.dbHost")
if ":" in hostport:
    host, port = hostport.rsplit(":", 1)
else:
    host, port = hostport, "5432"
if not port.isdigit():
    raise SystemExit(f"transitclock.db.dbHost port is not numeric: {hostport}")

def check_ident(label, value):
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_")
    if not value or any(char not in allowed for char in value):
        raise SystemExit(f"{label} must contain only letters, digits, and underscore")

db_name = need("transitclock.db.dbName")
db_user = need("transitclock.db.dbUserName")
check_ident("transitclock.db.dbName", db_name)
check_ident("transitclock.db.dbUserName", db_user)

pairs = {
    "DB_HOST": host,
    "DB_PORT": port,
    "DB_HOST_VALUE": hostport,
    "DB_NAME": db_name,
    "DB_USERNAME": db_user,
    "AGENCY_ID": need("transitclock.core.agencyId"),
}
with open("/tmp/transitclock-db.env", "w", encoding="utf-8") as out:
    for key, value in pairs.items():
        out.write(f"{key}={shlex.quote(value)}\n")
PY
  # shellcheck disable=SC1091
  source /tmp/transitclock-db.env
  rm -f /tmp/transitclock-db.env
}

write_nginx() {
  local ready_status="$1"
  local proxy="$2"
  python3 - "${ready_status}" "${proxy}" <<'PY'
import sys

ready, proxy = sys.argv[1], sys.argv[2]
config = """worker_processes 1;
error_log /dev/stderr info;

events {
  worker_connections 256;
}

http {
  access_log /dev/stdout;
  default_type application/octet-stream;
  proxy_read_timeout 120s;
  proxy_connect_timeout 10s;

  server {
    listen 8080;

    location = /health {
      access_log off;
      default_type text/plain;
      return 200 "ok\\n";
    }

    location = /ready {
      access_log off;
      default_type text/plain;
      return %s "%s\\n";
    }

%s
  }
}
""" % (ready, ready, proxy)
with open("/etc/nginx/nginx.conf", "w", encoding="utf-8") as handle:
    handle.write(config)
PY
}

waiting_proxy() {
  cat <<'EOF'
    location / {
      default_type text/plain;
      return 503 "TransitClock is still starting\n";
    }
EOF
}

live_proxy() {
  cat <<EOF
    location = /gtfs-rt/trip-updates {
      proxy_pass http://127.0.0.1:8081/api/v1/key/${API_KEY}/agency/${AGENCY_ID}/command/gtfs-rt/tripUpdates;
      proxy_set_header Host \$host;
    }

    location = /gtfs-rt/vehicle-positions {
      proxy_pass http://127.0.0.1:8081/api/v1/key/${API_KEY}/agency/${AGENCY_ID}/command/gtfs-rt/vehiclePositions;
      proxy_set_header Host \$host;
    }

    location /api/ {
      proxy_pass http://127.0.0.1:8081/api/;
      proxy_set_header Host \$host;
    }

    location /web/ {
      proxy_pass http://127.0.0.1:8081/web/;
      proxy_set_header Host \$host;
    }
EOF
}

wait_for_postgres() {
  until psql_exec -c "SELECT 1" >/dev/null 2>&1; do
    log "Waiting for PostgreSQL at ${DB_HOST}:${DB_PORT}/${DB_NAME}"
    sleep 5
  done
  log "PostgreSQL is accepting connections"
}

schema_ready() {
  local relation
  relation="$(psql_exec -tAc "SELECT to_regclass('public.activerevisions');" | tr -d '[:space:]')"
  [[ -n "${relation}" && "${relation}" != "NULL" ]]
}

ensure_schema() {
  if schema_ready; then
    log "Database schema already exists"
    return 0
  fi

  log "Generating PostgreSQL DDL"
  java "${JAVA_OPTS[@]}" -cp "${CP}" org.transitclock.applications.SchemaGenerator \
    -o "${TRANSITCLOCK_HOME}/db" \
    -p org.transitclock.db.structs
  java "${JAVA_OPTS[@]}" -cp "${CP}" org.transitclock.applications.SchemaGenerator \
    -o "${TRANSITCLOCK_HOME}/db" \
    -p org.transitclock.db.webstructs

  local structs="${TRANSITCLOCK_HOME}/db/ddl_postgres_org_transitclock_db_structs.sql"
  local webstructs="${TRANSITCLOCK_HOME}/db/ddl_postgres_org_transitclock_db_webstructs.sql"
  if [[ ! -f "${structs}" || ! -f "${webstructs}" ]]; then
    log "SchemaGenerator did not write the expected DDL files"
    ls -l "${TRANSITCLOCK_HOME}/db" || true
    exit 1
  fi

  log "Applying core and web DDL"
  psql_exec -f "${structs}"
  psql_exec -f "${webstructs}"
}

ensure_privileges() {
  psql_exec <<SQL
CREATE SEQUENCE IF NOT EXISTS hibernate_sequence START 1;
GRANT ALL ON ALL TABLES IN SCHEMA public TO ${DB_USERNAME};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${DB_USERNAME};
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO ${DB_USERNAME};
CREATE TABLE IF NOT EXISTS gtfs_source_state (
  id integer PRIMARY KEY,
  etag text NOT NULL,
  imported_at timestamp NOT NULL DEFAULT now()
);
SQL
}

wait_for_stable_zip() {
  local previous=0
  local size
  while true; do
    if [[ -f "${GTFS_ZIP}" ]]; then
      size="$(stat -c%s "${GTFS_ZIP}")"
      if [[ "${size}" -gt 0 && "${size}" == "${previous}" ]]; then
        return 0
      fi
      previous="${size}"
    fi
    log "Waiting for the GTFS zip at ${GTFS_ZIP}. Copy it onto the gtfs volume, or set GTFS_URL."
    sleep 15
  done
}

obtain_gtfs_zip() {
  if [[ -n "${GTFS_URL:-}" ]]; then
    log "Downloading GTFS zip"
    mkdir -p "$(dirname "${GTFS_ZIP}")"
    curl -fsSL "${GTFS_URL}" -o "${GTFS_ZIP}.partial"
    mv "${GTFS_ZIP}.partial" "${GTFS_ZIP}"
    return 0
  fi
  mkdir -p "$(dirname "${GTFS_ZIP}")"
  wait_for_stable_zip
}

import_gtfs_if_needed() {
  local rev_count stored current
  rev_count="$(psql_exec -tAc "SELECT count(*) FROM activerevisions WHERE configrev >= 0;" | tr -d '[:space:]')"
  stored="$(psql_exec -tAc "SELECT etag FROM gtfs_source_state WHERE id = 1;" | tr -d '[:space:]')"

  if [[ "${rev_count}" != "0" && -z "${GTFS_URL:-}" && ! -f "${GTFS_ZIP}" ]]; then
    log "GTFS revision is already stored and no new zip is mounted"
    return 0
  fi

  obtain_gtfs_zip
  current="$(sha256sum "${GTFS_ZIP}" | awk '{print $1}')"
  if [[ "${stored}" == "${current}" && "${rev_count}" != "0" ]]; then
    log "Static GTFS is already imported"
    return 0
  fi

  log "Importing static GTFS"
  java "${JAVA_OPTS[@]}" -Xmx"${CORE_XMX}" -cp "${CP}" \
    org.transitclock.applications.GtfsFileProcessor \
    -c "${CONFIG_FILES%%;*}" \
    -storeNewRevs \
    -skipDeleteRevs \
    -gtfsZipFileName "${GTFS_ZIP}" \
    -maxTravelTimeSegmentLength "${MAX_TRAVEL_TIME_SEGMENT_LENGTH}"

  psql_exec -c "UPDATE activerevisions SET configrev = 0 WHERE configrev = -1; UPDATE activerevisions SET traveltimesrev = 0 WHERE traveltimesrev = -1;"
  psql_exec -c "INSERT INTO gtfs_source_state (id, etag) VALUES (1, '${current}') ON CONFLICT (id) DO UPDATE SET etag = EXCLUDED.etag, imported_at = now();"
  log "GTFS import stored"
}

ensure_api_key() {
  local count
  count="$(psql_exec -tAc "SELECT count(*) FROM apikeys;" | tr -d '[:space:]')"
  if [[ "${count}" == "0" ]]; then
    log "Creating API key"
    java "${JAVA_OPTS[@]}" -cp "${CP}" org.transitclock.applications.CreateAPIKey \
      -c "${CONFIG_FILES%%;*}" \
      -n "${API_KEY_NAME}" \
      -u "${API_KEY_URL}" \
      -e "${API_KEY_EMAIL}" \
      -p "${API_KEY_PHONE}" \
      -d "${API_KEY_DESCRIPTION}"
  fi

  API_KEY="$(psql_exec -tAc "SELECT applicationkey FROM apikeys LIMIT 1;" | tr -d '[:space:]')"
  if [[ -z "${API_KEY}" ]]; then
    log "CreateAPIKey did not store an application key"
    exit 1
  fi
  if [[ ! "${API_KEY}" =~ ^[0-9a-fA-F]+$ ]]; then
    log "API key has unexpected characters and cannot be placed in the nginx config"
    exit 1
  fi
  export API_KEY
  log "API key is available for the GTFS-RT proxy"
}

ensure_web_agency() {
  local count
  count="$(psql_exec -tAc "SELECT count(*) FROM webagencies;" | tr -d '[:space:]')"
  if [[ "${count}" != "0" ]]; then
    log "WebAgency rows already exist"
    return 0
  fi

  log "Creating WebAgency"
  java "${JAVA_OPTS[@]}" -cp "${CP}" org.transitclock.db.webstructs.WebAgency \
    "${AGENCY_ID}" \
    "127.0.0.1" \
    "${DB_NAME}" \
    "postgresql" \
    "${DB_HOST_VALUE}" \
    "${DB_USERNAME}" \
    "${PGPASSWORD}"
}

wait_for_port() {
  local port="$1"
  local attempt
  for attempt in $(seq 1 60); do
    if (echo >/dev/tcp/127.0.0.1/"${port}") >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  log "Timed out waiting for port ${port}"
  return 1
}

wait_for_core() {
  local attempt
  for attempt in $(seq 1 360); do
    if grep -q "Starting up optional module\|No optional modules to start up" "${LOG_DIR}/core.log"; then
      log "TransitClock core is serving RMI"
      return 0
    fi
    if ! kill -0 "${CORE_PID}" 2>/dev/null; then
      log "Core exited before it finished starting. See ${LOG_DIR}/core.log"
      exit 1
    fi
    sleep 5
  done
  log "Core did not report ready within 30 minutes"
  exit 1
}

stop_children() {
  local pid
  for pid in "${CORE_PID:-}" "${TOMCAT_PID:-}" "${RMI_PID:-}" "${NGINX_PID:-}"; do
    if [[ -n "${pid}" ]]; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  nginx -s quit 2>/dev/null || true
}

require_env SCANIA_CLIENT_ID
require_env SCANIA_SECRET_KEY
require_env PGPASSWORD

if [[ ! -f "${CONFIG_FILES%%;*}" ]]; then
  log "transitclock.configFiles was not found at ${CONFIG_FILES}"
  exit 1
fi

load_config

if [[ "${AGENCY_ID}" == "CHANGE_ME" ]]; then
  log "Set transitclock.core.agencyId in transitclock.configFiles to the GTFS agency_id"
  exit 1
fi

if [[ -n "${POSTGRES_DB:-}" && "${POSTGRES_DB}" != "${DB_NAME}" ]]; then
  log "POSTGRES_DB (${POSTGRES_DB}) and transitclock.db.dbName (${DB_NAME}) must match"
  exit 1
fi
if [[ -n "${POSTGRES_USER:-}" && "${POSTGRES_USER}" != "${DB_USERNAME}" ]]; then
  log "POSTGRES_USER (${POSTGRES_USER}) and transitclock.db.dbUserName (${DB_USERNAME}) must match"
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  export JAVA_HOME
fi

JAVA_OPTS=(
  "-Duser.timezone=${USER_TIMEZONE}"
  "-Djava.rmi.server.hostname=127.0.0.1"
  "-Dtransitclock.configFiles=${CONFIG_FILES}"
  "-Dtransitclock.avl.scania.clientId=${SCANIA_CLIENT_ID}"
  "-Dtransitclock.avl.scania.secretKey=${SCANIA_SECRET_KEY}"
  "-Dtransitclock.db.dbName=${DB_NAME}"
  "-Dtransitclock.db.dbHost=${DB_HOST_VALUE}"
  "-Dtransitclock.db.dbUserName=${DB_USERNAME}"
  "-Dtransitclock.db.dbPassword=${PGPASSWORD}"
  "-Dtransitclock.db.dbType=postgresql"
  "-Dtransitclock.hibernate.configFile=${HIBERNATE}"
  "-Dtransitclock.core.agencyId=${AGENCY_ID}"
  "-Dtransitclock.logging.dir=${LOG_DIR}"
  "-Dtransitclock.rmi.secondaryRmiPort=0"
)

log "Using transitclock.configFiles=${CONFIG_FILES}"
log "Database ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
java -version

mkdir -p "${LOG_DIR}" "${TRANSITCLOCK_HOME}/cache" "${TRANSITCLOCK_HOME}/data" "${TRANSITCLOCK_HOME}/db"

write_nginx 503 "$(waiting_proxy)"
nginx -g 'daemon off;' &
NGINX_PID=$!
wait_for_port 8080
log "Health endpoint is up while GTFS and Core start"

wait_for_postgres
ensure_schema
ensure_privileges
import_gtfs_if_needed
ensure_api_key
ensure_web_agency

TC_OPTS=("${JAVA_OPTS[@]}")
export JAVA_OPTS="-Xms256m -Xmx512m ${TC_OPTS[*]}"
export CATALINA_OPTS="${JAVA_OPTS}"

log "Starting RMI registry"
rmiregistry -J-Djava.rmi.server.hostname=127.0.0.1 >>"${LOG_DIR}/rmi.log" 2>&1 &
RMI_PID=$!

touch "${LOG_DIR}/core.log"
log "Starting TransitClock core"
java -Xss2m -Xms512m -Xmx"${CORE_XMX}" "${TC_OPTS[@]}" -cp "${CP}" \
  org.transitclock.applications.Core >>"${LOG_DIR}/core.log" 2>&1 &
CORE_PID=$!
tail -n +1 -F "${LOG_DIR}/core.log" &

wait_for_core

log "Starting Tomcat"
"${CATALINA_HOME}/bin/catalina.sh" run &
TOMCAT_PID=$!
wait_for_port 8081

write_nginx 200 "$(live_proxy)"
nginx -s reload
log "GTFS-RT feeds are published on /gtfs-rt/trip-updates and /gtfs-rt/vehicle-positions"

trap 'stop_children; exit 0' TERM INT

while true; do
  if ! kill -0 "${CORE_PID}" 2>/dev/null; then
    log "Core process exited"
    stop_children
    exit 1
  fi
  if ! kill -0 "${TOMCAT_PID}" 2>/dev/null; then
    log "Tomcat process exited"
    stop_children
    exit 1
  fi
  if ! kill -0 "${NGINX_PID}" 2>/dev/null; then
    log "Nginx process exited"
    stop_children
    exit 1
  fi
  sleep 15
done

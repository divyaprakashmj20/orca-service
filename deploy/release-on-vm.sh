#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
release_sha=${1:?Pass the release commit SHA}
[[ "$release_sha" =~ ^[a-f0-9]{40}$ ]] || { echo 'Invalid release SHA'; exit 1; }
deploy_root=/opt/orka
staging_root="/tmp/orka-release-$release_sha"
attempt_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
release_root="$deploy_root/releases/$release_sha/$attempt_id"
rollback_tag="$release_sha-$attempt_id"
base_compose="$deploy_root/docker-compose.yml"
release_compose="$release_root/release.compose.yaml"
env_file="$deploy_root/.env"
command -v python3 >/dev/null
command -v curl >/dev/null
test -f "$base_compose"
test -f "$env_file"
test -s "$deploy_root/firebase-config.json"
test -s "$deploy_root/firebase-admin-sdk.json"
test -f "$staging_root/deploy/release.compose.yaml"

# Only replace the applications. Preserve database image/volume, secrets and Caddy.
export ORKA_RELEASE_SHA="$release_sha"
base() { sudo docker compose --env-file "$env_file" -f "$base_compose" "$@"; }
compose() { sudo ORKA_RELEASE_SHA="$release_sha" docker compose --env-file "$env_file" -f "$base_compose" -f "$release_compose" "$@"; }
service_container=$(base ps -q orka-service)
ui_container=$(base ps -q orka-ui)
postgres_container=$(base ps -q postgres)
test -n "$service_container" && test -n "$ui_container" && test -n "$postgres_container"
service_image=$(sudo docker inspect --format '{{.Image}}' "$service_container")
ui_image=$(sudo docker inspect --format '{{.Image}}' "$ui_container")
[[ "$service_image" =~ ^sha256:[a-f0-9]{64}$ && "$ui_image" =~ ^sha256:[a-f0-9]{64}$ ]]
sudo install -d -m 700 -o "$(id -u)" -g "$(id -g)" "$release_root"
install -m 600 "$staging_root/deploy/release.compose.yaml" "$release_compose"
cp "$base_compose" "$release_root/base-compose.before.yaml"
base config --quiet
compose config --quiet
base config --format json | python3 -c 'import json,sys; j=json.load(sys.stdin); assert j["services"]["orka-service"]["environment"].get("SPRING_JPA_HIBERNATE_DDL_AUTO")=="update", "The current application deployment must support its additive schema updates"'
sudo docker tag "$service_image" "orka-rollback-service:$rollback_tag"
sudo docker tag "$ui_image" "orka-rollback-ui:$rollback_tag"
cat >"$release_root/rollback.compose.yaml" <<EOF
services:
  orka-service:
    image: orka-rollback-service:$rollback_tag
  orka-ui:
    image: orka-rollback-ui:$rollback_tag
EOF

# Confirm a full logical backup before applying additive schema changes.
base exec -T postgres sh -c 'exec pg_dump --format=custom --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' >"$release_root/database.before.dump"
test -s "$release_root/database.before.dump"
base exec -T postgres sh -c 'exec pg_restore --list' <"$release_root/database.before.dump" >"$release_root/database.before.contents"
test -s "$release_root/database.before.contents"
# Existing registrations stay on the legacy renderer until an updated APK registers.
base exec -T postgres sh -c 'exec psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' <<'SQL'
ALTER TABLE device_tokens ADD COLUMN IF NOT EXISTS notification_renderer_version integer NOT NULL DEFAULT 0;
SQL
registry_auth_root="$release_root/.registry-auth"
registry_logout() {
  sudo docker --config "$registry_auth_root" logout ghcr.io >/dev/null 2>&1 || true
  sudo rm -f -- "$registry_auth_root/config.json"
}
trap registry_logout EXIT
test -n "${ORKA_REGISTRY_USERNAME:-}"
test -n "${ORKA_REGISTRY_PASSWORD:-}"
sudo install -d -m 700 "$registry_auth_root"
printf '%s' "$ORKA_REGISTRY_PASSWORD" | sudo docker --config "$registry_auth_root" login ghcr.io --username "$ORKA_REGISTRY_USERNAME" --password-stdin
unset ORKA_REGISTRY_PASSWORD
sudo DOCKER_CONFIG="$registry_auth_root" ORKA_RELEASE_SHA="$release_sha" docker compose --env-file "$env_file" -f "$base_compose" -f "$release_compose" pull orka-service orka-ui
registry_logout
trap - EXIT
changed=0
rollback() {
  result=$?
  if [[ $changed == 1 ]]; then
    echo 'Release checks failed; restoring previous application images.'
    if sudo docker compose --env-file "$env_file" -f "$base_compose" -f "$release_root/rollback.compose.yaml" up -d --no-deps --pull never orka-service orka-ui; then
      echo 'Previous application images restored. Check their health before retrying.'
    else
      echo "ROLLBACK FAILED: manual recovery required using $release_root/rollback.compose.yaml" >&2
    fi
    echo 'Database backup retained. Database data is never automatically overwritten.'
  fi
  exit "$result"
}
trap rollback ERR
changed=1
compose up -d --no-deps orka-service orka-ui
healthy=0
for attempt in $(seq 1 45); do
  if curl --fail --silent --max-time 8 http://127.0.0.1:8082/api/notifications/health >"$release_root/health.json" &&
    python3 -c 'import json,sys; j=json.load(open(sys.argv[1])); assert j.get("fcmEnabled") is True and j.get("localTestDeviceTokenId") is None' "$release_root/health.json"; then
    healthy=1
    break
  fi
  sleep 2
done
[[ $healthy == 1 ]]
compose exec -T orka-ui wget -qO- http://127.0.0.1/release.json >"$release_root/web-release.json"
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["release"]==sys.argv[2]' "$release_root/web-release.json" "$release_sha"
compose exec -T orka-ui wget -qO- http://127.0.0.1/api/notifications/health >"$release_root/web-api-health.json"
python3 -c 'import json,sys; j=json.load(open(sys.argv[1])); assert j.get("fcmEnabled") is True and j.get("localTestDeviceTokenId") is None' "$release_root/web-api-health.json"
printf '%s\n' "$release_sha" >"$release_root/current-release.tmp"
sudo install -m 600 "$release_root/current-release.tmp" "$deploy_root/current-release.new"
sudo mv -- "$deploy_root/current-release.new" "$deploy_root/current-release"
trap - ERR
echo "Deployed web UI and backend $release_sha. Previous images and backup retained in $release_root."

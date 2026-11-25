#!/usr/bin/env bash
# Daily backup of one customer's DroneRadar database (installed by install.sh as a cron job).
#   ./backup.sh <customer-slug>      → /var/backups/droneradar/<slug>/YYYY-MM-DD.sql.gz (14 kept)
set -euo pipefail
SLUG=${1:?slug}
DIR="/opt/droneradar/$SLUG"
OUT="/var/backups/droneradar/$SLUG"
mkdir -p "$OUT"; chmod 700 "$OUT"
cd "$DIR"
PW=$(grep '^POSTGRES_PASSWORD=' .env | cut -d= -f2-)
docker compose -p "dr-$SLUG" exec -T -e PGPASSWORD="$PW" db \
  pg_dump -h localhost -U postgres -d postgres --schema=public --schema=auth --schema=storage \
  | gzip > "$OUT/$(date +%F).sql.gz"
find "$OUT" -name '*.sql.gz' -mtime +14 -delete

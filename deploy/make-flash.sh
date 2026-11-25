#!/usr/bin/env bash
# =====================================================================
#  DroneRadar — prepare an offline install USB (run on YOUR machine, with internet + Docker)
#
#    ./make-flash.sh /media/USB  path/to/DroneRadar-client-release.apk
#
#  The USB then holds everything a customer server needs, with no internet:
#    droneradar/ install.sh  images.tar.gz  supabase/  sql/  app/  README_AR.md
# =====================================================================
set -euo pipefail

OUT=${1:?"usage: make-flash.sh <usb-folder> [client.apk]"}
APK=${2:-}
SB_REF=b2cd26e0546f801946226a1096b49d4a04ab586c   # Supabase self-hosting files, pinned (Nov 2026)
HERE=$(cd "$(dirname "$0")" && pwd)
D="$OUT/droneradar"

command -v docker >/dev/null || { echo "Docker is required"; exit 1; }
command -v git >/dev/null    || { echo "git is required"; exit 1; }
mkdir -p "$D"

echo "1/4  Supabase self-hosting files ($SB_REF)"
tmp=$(mktemp -d)
git clone -q --filter=blob:none --no-checkout https://github.com/supabase/supabase "$tmp/sb"
( cd "$tmp/sb" && git sparse-checkout set docker && git checkout -q "$SB_REF" )
rm -rf "$D/supabase"; cp -r "$tmp/sb/docker" "$D/supabase"; rm -rf "$tmp"
cp "$HERE/docker-compose.droneradar.yml" "$D/supabase/"

echo "2/4  Docker images (this is the big part, ~3 GB)"
( cd "$D/supabase" && cp .env.example .env \
  && COMPOSE_FILE=docker-compose.yml:docker-compose.caddy.yml:docker-compose.droneradar.yml docker compose config --images ) \
  | sort -u > "$D/images.txt"
while read -r img; do docker pull -q "$img"; done < "$D/images.txt"
rm -f "$D/supabase/.env"
docker save $(cat "$D/images.txt") | gzip -1 > "$D/images.tar.gz"

echo "3/4  DroneRadar database + scripts"
rm -rf "$D/sql"; mkdir -p "$D/sql"
n=10
for f in schema schema_phase2 schema_phase3 schema_sightings_whitelist schema_spoof schema_reports \
         schema_security_v1 schema_retention schema_global_v1 schema_fixes_v2; do
  cp "$HERE/../server/$f.sql" "$D/sql/${n}_$f.sql"; n=$((n + 10))
done
cp "$HERE/install.sh" "$HERE/backup.sh" "$HERE/README_AR.md" "$D/"
chmod +x "$D/install.sh" "$D/backup.sh"

echo "4/4  Phone app"
mkdir -p "$D/app"
if [ -n "$APK" ]; then cp "$APK" "$D/app/DroneRadar.apk"; else echo "   (no APK given — copy the 'client' APK into $D/app/ yourself)"; fi

( cd "$D" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
echo "Done → $D  ($(du -sh "$D" | cut -f1))"

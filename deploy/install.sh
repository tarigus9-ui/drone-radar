#!/usr/bin/env bash
# =====================================================================
#  DroneRadar — install ONE customer's private server from the USB (no internet needed)
#
#    sudo ./install.sh                       # asks the questions
#    sudo ./install.sh --name "Emaar" --host 10.20.0.5 --admin admin@emaar.ae
#
#  Every customer gets a separate installation: own database, own keys, own certificate,
#  own folder /opt/droneradar/<name>. Nothing is shared with other customers or with us.
#  Needs: Linux server (Ubuntu 22.04+ recommended), Docker Engine + Compose v2.24+, 4 CPU / 8 GB RAM.
# =====================================================================
set -euo pipefail

SRC=$(cd "$(dirname "$0")" && pwd)
NAME="" HOST="" ADMIN="" REGION="" TZNAME="Asia/Dubai"
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --admin) ADMIN="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    *) echo "unknown option $1"; exit 1 ;;
  esac
done

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✖ %s\033[0m\n' "$*"; exit 1; }

[ "$(id -u)" = 0 ] || die "Run with sudo"
command -v docker >/dev/null || die "Docker is not installed"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2 is not installed"
command -v openssl >/dev/null || die "openssl is not installed"
command -v curl >/dev/null || die "curl is not installed"

[ -n "$NAME" ]  || read -rp "Customer / company name (e.g. Emaar): " NAME
[ -n "$HOST" ]  || read -rp "Server address the phones will use (LAN IP or DNS name): " HOST
[ -n "$ADMIN" ] || read -rp "First admin e-mail: " ADMIN
[ -n "$REGION" ] || REGION="UAE"
SLUG=$(echo "$NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-//;s/-$//')
[ -n "$SLUG" ] || SLUG="customer"
DIR="/opt/droneradar/$SLUG"
[ -e "$DIR/.env" ] && die "$DIR already exists — this customer is already installed"

say "1/7  Loading Docker images from the USB"
if [ -f "$SRC/images.tar.gz" ]; then
  missing=0; while read -r img; do docker image inspect "$img" >/dev/null 2>&1 || missing=1; done < "$SRC/images.txt"
  if [ "$missing" = 1 ]; then gunzip -c "$SRC/images.tar.gz" | docker load; else echo "   already loaded"; fi
fi

say "2/7  Creating $DIR"
mkdir -p "$DIR"; cp -r "$SRC/supabase/." "$DIR/"; cd "$DIR"
cp .env.example .env
sh utils/generate-keys.sh --update-env >/dev/null
setenv() { if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi; }
setenv COMPOSE_FILE "docker-compose.yml:docker-compose.caddy.yml:docker-compose.droneradar.yml"
setenv SUPABASE_PUBLIC_URL "https://$HOST"
setenv API_EXTERNAL_URL "https://$HOST"
setenv SITE_URL "https://$HOST"
setenv PROXY_DOMAIN "$HOST"
setenv ENABLE_EMAIL_SIGNUP true          # staff join with an invite code from their admin
setenv ENABLE_EMAIL_AUTOCONFIRM true     # no mail server on-prem
setenv ENABLE_ANONYMOUS_USERS false
setenv ENABLE_PHONE_SIGNUP false
setenv DASHBOARD_USERNAME droneradar
chmod 600 .env
set -a; . ./.env; set +a

say "3/7  Own HTTPS certificate for $HOST (10 years)"
mkdir -p volumes/proxy/caddy/certs
if echo "$HOST" | grep -Eq '^[0-9]+(\.[0-9]+){3}$'; then SAN="IP:$HOST"; else SAN="DNS:$HOST"; fi
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
  -keyout volumes/proxy/caddy/certs/key.pem -out volumes/proxy/caddy/certs/cert.pem \
  -subj "/CN=$HOST/O=DroneRadar $NAME" -addext "subjectAltName=$SAN" 2>/dev/null
chmod 600 volumes/proxy/caddy/certs/key.pem
PIN=$(openssl x509 -in volumes/proxy/caddy/certs/cert.pem -pubkey -noout | openssl pkey -pubin -outform der \
      | openssl dgst -sha256 -binary | openssl enc -base64 -A)
cat > volumes/proxy/caddy/Caddyfile <<'CADDY'
{
	auto_https off
}
:443 {
	tls /etc/caddy/certs/cert.pem /etc/caddy/certs/key.pem
	@supabase_api path /auth/v1/* /rest/v1/* /graphql/v1 /realtime/v1/* /storage/v1/*
	handle @supabase_api {
		reverse_proxy api-gw:8000
	}
	handle {
		basic_auth {
			import /config/dashboard-auth
		}
		reverse_proxy studio:3000
	}
	header -server
}
CADDY

say "4/7  Starting the server (first start takes a few minutes)"
docker compose -p "dr-$SLUG" up -d
api() { curl -sk --max-time 5 "https://127.0.0.1$1" -H "apikey: $SERVICE_ROLE_KEY" -H "Authorization: Bearer $SERVICE_ROLE_KEY" "${@:2}"; }
for i in $(seq 1 90); do
  code=$(curl -sk -o /dev/null -w '%{http_code}' --max-time 5 "https://127.0.0.1/auth/v1/health" -H "apikey: $ANON_KEY" || true)
  [ "$code" = 200 ] && break; sleep 5
done
[ "$code" = 200 ] || die "Server did not become ready — check: docker compose -p dr-$SLUG ps"

say "5/7  DroneRadar database"
psqlc() { docker compose -p "dr-$SLUG" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" db \
            psql -h localhost -U postgres -d postgres -v ON_ERROR_STOP=1 -q "$@"; }
for f in "$SRC"/sql/*.sql; do echo "   $(basename "$f")"; psqlc < "$f" >/dev/null; done

say "6/7  Company and first admin"
ADMIN_PASS=$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)
uid=$(api /auth/v1/admin/users -X POST -H 'Content-Type: application/json' \
      -d "{\"email\":\"$ADMIN\",\"password\":\"$ADMIN_PASS\",\"email_confirm\":true}" \
      | sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p' | head -1)
[ -n "$uid" ] || die "Could not create the admin account"
NAME_SQL=$(printf "%s" "$NAME" | sed "s/'/''/g"); REGION_SQL=$(printf "%s" "$REGION" | sed "s/'/''/g")
psqlc <<SQL
with c as (insert into public.companies (name) values ('$NAME_SQL') returning id),
     r as (insert into public.regions (company_id, name, country_code, timezone)
           select id, '$REGION_SQL', 'AE', '$TZNAME' from c returning id, company_id)
insert into public.profiles (user_id, company_id, full_name, role, region_id)
select '$uid', company_id, 'Admin', 'admin', id from r;
SQL

say "7/7  Phone setup code"
NAME_JSON=$(printf '%s' "$NAME" | sed 's/\\/\\\\/g; s/"/\\"/g')
CFG=$(printf '{"v":1,"n":"%s","u":"https://%s","k":"%s","p":"%s"}' "$NAME_JSON" "$HOST" "$ANON_KEY" "$PIN")
LINK="droneradar://setup?d=$(printf '%s' "$CFG" | openssl enc -base64 -A | tr '+/' '-_' | tr -d '=')"
printf '%s\n' "$CFG" > setup-code.txt
if command -v qrencode >/dev/null; then
  qrencode -o setup-qr.png -s 8 "$CFG"; qrencode -t ANSIUTF8 "$CFG"
else
  echo "   (install 'qrencode' to print the QR; the text code below works with 'paste' in the app)"
fi

# Daily database backup, 14 days kept
cp "$SRC/backup.sh" "$DIR/backup.sh"; chmod +x "$DIR/backup.sh"
echo "23 2 * * * root $DIR/backup.sh $SLUG" > "/etc/cron.d/droneradar-$SLUG"

umask 077
cat > credentials.txt <<TXT
DroneRadar — $NAME   (installed $(date -u +%F))
Server address ........ https://$HOST
First admin ........... $ADMIN  /  $ADMIN_PASS      (change it after first login)
Database dashboard .... https://$HOST   user: droneradar  password: $DASHBOARD_PASSWORD
Certificate pin ....... $PIN
Phone setup code ...... $CFG
Phone setup link ...... $LINK
Folder ................ $DIR   (keep .env and this file secret — back them up offline)
TXT

say "Done ✅"
echo "   Credentials:  $DIR/credentials.txt"
echo "   Phone QR:     $DIR/setup-qr.png  (or send the setup link / code)"
echo "   App:          $SRC/app/DroneRadar.apk"

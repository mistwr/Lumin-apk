#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${1:-$HERE/.env}"
OUT_DIR="${2:-$HERE/rendered}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy .env.example to .env and fill the values." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required=(
  ASTERISK_PUBLIC_IP ASTERISK_LOCAL_NET TWILIO_TRUNK_DOMAIN
  TWILIO_CALLER_ID_E164 TWILIO_SIP_USERNAME TWILIO_SIP_PASSWORD
  TWILIO_SIGNALING_CIDR_1 TWILIO_SIGNALING_CIDR_2
  REBORN_SIP_PASSWORD ARI_PASSWORD
)
for key in "${required[@]}"; do
  [[ -n "${!key:-}" ]] || { echo "Missing $key" >&2; exit 1; }
done

mkdir -p "$OUT_DIR"

render() {
  local src="$1" dst="$2"
  sed \
    -e "s|__ASTERISK_PUBLIC_IP__|$ASTERISK_PUBLIC_IP|g" \
    -e "s|__ASTERISK_LOCAL_NET__|$ASTERISK_LOCAL_NET|g" \
    -e "s|__TWILIO_TRUNK_DOMAIN__|$TWILIO_TRUNK_DOMAIN|g" \
    -e "s|__TWILIO_CALLER_ID_E164__|$TWILIO_CALLER_ID_E164|g" \
    -e "s|__TWILIO_SIP_USERNAME__|$TWILIO_SIP_USERNAME|g" \
    -e "s|__TWILIO_SIP_PASSWORD__|$TWILIO_SIP_PASSWORD|g" \
    -e "s|__TWILIO_SIGNALING_CIDR_1__|$TWILIO_SIGNALING_CIDR_1|g" \
    -e "s|__TWILIO_SIGNALING_CIDR_2__|$TWILIO_SIGNALING_CIDR_2|g" \
    -e "s|__REBORN_SIP_PASSWORD__|$REBORN_SIP_PASSWORD|g" \
    -e "s|__ARI_PASSWORD__|$ARI_PASSWORD|g" \
    "$src" > "$dst"
}

render "$HERE/pjsip.conf" "$OUT_DIR/pjsip.conf"
render "$HERE/extensions.conf" "$OUT_DIR/extensions.conf"
render "$HERE/ari.conf" "$OUT_DIR/ari.conf"
cp "$HERE/http.conf" "$OUT_DIR/http.conf"
cp "$HERE/rtp.conf" "$OUT_DIR/rtp.conf"

printf 'Rendered Asterisk config in %s\n' "$OUT_DIR"
printf 'Copy *.conf to /etc/asterisk/ then run: asterisk -rx "core reload"\n'

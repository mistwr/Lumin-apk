#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${1:-$HERE/.env}"

command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq required" >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "Missing $ENV_FILE" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required=(TWILIO_ACCOUNT_SID TWILIO_AUTH_TOKEN TWILIO_TRUNK_NAME TWILIO_TRUNK_DOMAIN ASTERISK_PUBLIC_HOST TWILIO_SIP_USERNAME TWILIO_SIP_PASSWORD)
for key in "${required[@]}"; do
  [[ -n "${!key:-}" ]] || { echo "Missing $key" >&2; exit 1; }
done

AUTH=(--user "$TWILIO_ACCOUNT_SID:$TWILIO_AUTH_TOKEN")

api_post() {
  local url="$1"; shift
  curl -fsS "${AUTH[@]}" -X POST "$url" "$@"
}

echo "[1/5] Creating Twilio Elastic SIP Trunk..."
TRUNK_JSON=$(api_post "https://trunking.twilio.com/v1/Trunks" \
  --data-urlencode "FriendlyName=$TWILIO_TRUNK_NAME" \
  --data-urlencode "DomainName=$TWILIO_TRUNK_DOMAIN" \
  --data-urlencode "Secure=false" \
  --data-urlencode "TransferMode=disable-all")
TRUNK_SID=$(jq -r '.sid' <<<"$TRUNK_JSON")
[[ "$TRUNK_SID" == TK* ]] || { echo "$TRUNK_JSON"; exit 1; }
echo "Trunk SID: $TRUNK_SID"

echo "[2/5] Creating SIP credential list..."
CL_JSON=$(api_post "https://api.twilio.com/2010-04-01/Accounts/$TWILIO_ACCOUNT_SID/SIP/CredentialLists.json" \
  --data-urlencode "FriendlyName=${TWILIO_TRUNK_NAME}-asterisk")
CL_SID=$(jq -r '.sid' <<<"$CL_JSON")
[[ "$CL_SID" == CL* ]] || { echo "$CL_JSON"; exit 1; }

echo "[3/5] Creating digest credential..."
api_post "https://api.twilio.com/2010-04-01/Accounts/$TWILIO_ACCOUNT_SID/SIP/CredentialLists/$CL_SID/Credentials.json" \
  --data-urlencode "Username=$TWILIO_SIP_USERNAME" \
  --data-urlencode "Password=$TWILIO_SIP_PASSWORD" >/dev/null

echo "[4/5] Linking credential list to trunk..."
api_post "https://trunking.twilio.com/v1/Trunks/$TRUNK_SID/CredentialLists" \
  --data-urlencode "CredentialListSid=$CL_SID" >/dev/null

echo "[5/5] Creating PSTN -> Asterisk origination URL..."
ORIG_URI="sip:${ASTERISK_PUBLIC_HOST}:5060;transport=udp"
api_post "https://trunking.twilio.com/v1/Trunks/$TRUNK_SID/OriginationUrls" \
  --data-urlencode "FriendlyName=REBORN-Asterisk" \
  --data-urlencode "SipUrl=$ORIG_URI" \
  --data-urlencode "Priority=10" \
  --data-urlencode "Weight=10" \
  --data-urlencode "Enabled=true" >/dev/null

cat <<EOF

Twilio trunk created.
Trunk SID:       $TRUNK_SID
Credential SID:  $CL_SID
Termination URI: sip:$TWILIO_TRUNK_DOMAIN
Origination URI: $ORIG_URI

Next:
1. Associate your Twilio phone number with trunk $TRUNK_SID.
2. Render/copy the Asterisk configs with ./render-config.sh.
3. Open UDP 5060 and RTP UDP 10000-20000 on the Asterisk host.
4. Run: asterisk -rx 'pjsip show endpoints'
5. Run: asterisk -rx 'pjsip show endpoint twilio'
EOF

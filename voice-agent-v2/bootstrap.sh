#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$ROOT_DIR/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy .env.example to .env first."
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

VOICEBRIDGE_REPO="${VOICEBRIDGE_REPO:-https://github.com/asterisk/AsteriskVoiceBridge.git}"
VOICEBRIDGE_REF="${VOICEBRIDGE_REF:-master}"
VOICEBRIDGE_DIR="${VOICEBRIDGE_DIR:-/opt/reborn/AsteriskVoiceBridge}"

if ! command -v git >/dev/null 2>&1; then
  echo "git is required"
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "Go is required for AsteriskVoiceBridge"
  exit 1
fi

sudo mkdir -p "$(dirname "$VOICEBRIDGE_DIR")"
sudo chown "$(id -u):$(id -g)" "$(dirname "$VOICEBRIDGE_DIR")"

if [[ -d "$VOICEBRIDGE_DIR/.git" ]]; then
  echo "Updating existing AsteriskVoiceBridge checkout..."
  git -C "$VOICEBRIDGE_DIR" fetch --all --tags
else
  echo "Cloning AsteriskVoiceBridge..."
  git clone "$VOICEBRIDGE_REPO" "$VOICEBRIDGE_DIR"
fi

git -C "$VOICEBRIDGE_DIR" checkout "$VOICEBRIDGE_REF"

echo "Building upstream voice bridge..."
(
  cd "$VOICEBRIDGE_DIR"
  go mod download
  go build ./...
)

cat <<EOF

REBORN Voice Agent V2 bootstrap complete.

Upstream checkout: $VOICEBRIDGE_DIR
ARI URL: ${ASTERISK_ARI_URL:-http://127.0.0.1:8088}
Stasis app: ${ASTERISK_STASIS_APP:-reborn-voice-v2}

Next checks:
  1. Asterisk ARI is reachable.
  2. Twilio trunk reaches Asterisk.
  3. Dialplan sends the test extension/call into Stasis.
  4. Start VoiceBridge with the provider keys required by the upstream demo.

Do not commit .env.
EOF

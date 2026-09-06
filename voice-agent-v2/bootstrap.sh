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
REBORN_BRAIN_URL="${REBORN_BRAIN_URL:-http://127.0.0.1:8080}"

for bin in git go python3 curl; do
  command -v "$bin" >/dev/null 2>&1 || { echo "$bin is required"; exit 1; }
done

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
git -C "$VOICEBRIDGE_DIR" reset --hard "origin/${VOICEBRIDGE_REF}" 2>/dev/null || true

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "Starting REBORN local brain + Ollama..."
  (cd "$ROOT_DIR" && docker compose up -d --build)
  echo "Pulling local Qwen model..."
  docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T ollama ollama pull "${OLLAMA_MODEL:-qwen3:1.7b}"
else
  echo "WARNING: docker compose not found; REBORN brain must already be running at $REBORN_BRAIN_URL"
fi

for i in $(seq 1 40); do
  if curl -fsS "$REBORN_BRAIN_URL/health" >/dev/null 2>&1; then
    echo "REBORN brain healthy at $REBORN_BRAIN_URL"
    break
  fi
  if [[ "$i" == "40" ]]; then
    echo "REBORN brain did not become healthy: $REBORN_BRAIN_URL"
    exit 1
  fi
  sleep 2
done

echo "Patching upstream VoiceBridge to bypass OpenAI and use REBORN brain..."
python3 "$ROOT_DIR/patch_voicebridge.py" "$VOICEBRIDGE_DIR"

echo "Formatting and building patched VoiceBridge..."
(
  cd "$VOICEBRIDGE_DIR"
  gofmt -w voicebot/voicebot.go
  go mod download
  go build ./...
)

cat > "$ROOT_DIR/run-voicebridge.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
export REBORN_BRAIN_URL='${REBORN_BRAIN_URL}'
export DG_API_TOKEN='${DG_API_TOKEN:-}'
cd '${VOICEBRIDGE_DIR}'
exec go run .
EOF
chmod +x "$ROOT_DIR/run-voicebridge.sh"

cat <<EOF

REBORN Voice Agent V2 is wired.

Upstream checkout: $VOICEBRIDGE_DIR
Brain: $REBORN_BRAIN_URL
ARI URL: ${ASTERISK_ARI_URL:-http://127.0.0.1:8088}
Deepgram STT/TTS: ${DG_API_TOKEN:+configured}${DG_API_TOKEN:-not-configured}

Runtime flow:
  Twilio -> Asterisk -> ARI/ExternalMedia -> Deepgram STT -> REBORN Qwen -> Deepgram TTS -> caller

Start bridge:
  $ROOT_DIR/run-voicebridge.sh

Required before a real PSTN call:
  - Asterisk reachable from Twilio SIP trunk.
  - ARI credentials/config match the VoiceBridge/Asterisk config.
  - DG_API_TOKEN configured (until STT/TTS is replaced locally).
  - Twilio trunk/account configured with real credentials and public SIP endpoint.
EOF

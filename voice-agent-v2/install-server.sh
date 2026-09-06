#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
ENV_FILE="$HERE/.env"

if [[ $EUID -ne 0 ]]; then
  echo "Run with sudo: sudo ./install-server.sh" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy .env.example to .env and fill it first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

required=(ASTERISK_PUBLIC_IP ASTERISK_LOCAL_NET ASTERISK_ARI_USER ASTERISK_ARI_PASSWORD ASTERISK_STASIS_APP ARI_PASSWORD REBORN_SIP_PASSWORD TWILIO_TRUNK_DOMAIN TWILIO_CALLER_ID_E164 TWILIO_SIP_USERNAME TWILIO_SIP_PASSWORD TWILIO_SIGNALING_CIDR_1 TWILIO_SIGNALING_CIDR_2 DG_API_TOKEN)
for k in "${required[@]}"; do
  [[ -n "${!k:-}" ]] || { echo "Missing $k" >&2; exit 1; }
done

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y asterisk git curl ca-certificates python3 python3-venv golang-go docker.io docker-compose-plugin
systemctl enable --now docker
systemctl enable --now asterisk

# Asterisk configs
mkdir -p "$ROOT/infra/asterisk"
cp "$ENV_FILE" "$ROOT/infra/asterisk/.env"
chmod +x "$ROOT/infra/asterisk/render-config.sh"
"$ROOT/infra/asterisk/render-config.sh" "$ROOT/infra/asterisk/.env" "$ROOT/infra/asterisk/rendered"
install -m 0640 -o asterisk -g asterisk "$ROOT/infra/asterisk/rendered/pjsip.conf" /etc/asterisk/pjsip.conf
install -m 0640 -o asterisk -g asterisk "$ROOT/infra/asterisk/rendered/extensions.conf" /etc/asterisk/extensions.conf
install -m 0640 -o asterisk -g asterisk "$ROOT/infra/asterisk/rendered/ari.conf" /etc/asterisk/ari.conf
install -m 0640 -o asterisk -g asterisk "$ROOT/infra/asterisk/rendered/http.conf" /etc/asterisk/http.conf
install -m 0640 -o asterisk -g asterisk "$ROOT/infra/asterisk/rendered/rtp.conf" /etc/asterisk/rtp.conf
asterisk -rx 'core reload' || systemctl restart asterisk

# Local Qwen brain
cd "$HERE"
docker compose up -d --build
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then break; fi
  sleep 2
done
docker compose exec -T ollama ollama pull "${OLLAMA_MODEL:-qwen3:1.7b}"
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8080/health

# VoiceBridge upstream + REBORN patch
VB_DIR="${VOICEBRIDGE_DIR:-/opt/reborn/AsteriskVoiceBridge}"
mkdir -p "$(dirname "$VB_DIR")"
if [[ -d "$VB_DIR/.git" ]]; then
  git -C "$VB_DIR" fetch --all --tags
else
  git clone "${VOICEBRIDGE_REPO:-https://github.com/asterisk/AsteriskVoiceBridge.git}" "$VB_DIR"
fi
git -C "$VB_DIR" checkout "${VOICEBRIDGE_REF:-master}"
git -C "$VB_DIR" reset --hard "origin/${VOICEBRIDGE_REF:-master}" 2>/dev/null || true
python3 "$HERE/patch_voicebridge.py" "$VB_DIR"
cd "$VB_DIR"
go mod download
go build -o /usr/local/bin/reborn-voicebridge .

# Runtime environment, readable only by root.
install -m 0600 "$ENV_FILE" /etc/reborn-voice-agent.env

cat >/etc/systemd/system/reborn-voicebridge.service <<'EOF'
[Unit]
Description=REBORN AI Asterisk VoiceBridge
After=network-online.target asterisk.service docker.service
Wants=network-online.target
Requires=asterisk.service

[Service]
Type=simple
EnvironmentFile=/etc/reborn-voice-agent.env
WorkingDirectory=/opt/reborn/AsteriskVoiceBridge
ExecStart=/usr/local/bin/reborn-voicebridge
Restart=always
RestartSec=2
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now reborn-voicebridge
sleep 3

cat <<EOF

REBORN Voice Agent V2 installed.

Checks:
  Brain:       http://127.0.0.1:8080/health
  ARI:         http://127.0.0.1:8088/ari/api-docs/resources.json
  SIP:         UDP ${ASTERISK_PUBLIC_IP}:5060
  RTP:         UDP ${ASTERISK_PUBLIC_IP}:10000-20000
  Stasis app:  ${ASTERISK_STASIS_APP}

Run:
  asterisk -rx 'pjsip show endpoints'
  asterisk -rx 'http show status'
  systemctl status reborn-voicebridge --no-pager
  journalctl -u reborn-voicebridge -f

Next external step: point the Twilio trunk Origination URI at sip:${ASTERISK_PUBLIC_IP}:5060 and use ${TWILIO_TRUNK_DOMAIN} for termination.
EOF

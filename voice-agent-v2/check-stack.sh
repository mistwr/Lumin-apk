#!/usr/bin/env bash
set -u
fail=0
ok(){ printf 'OK   %s\n' "$1"; }
bad(){ printf 'FAIL %s\n' "$1"; fail=1; }

curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1 && ok 'REBORN brain' || bad 'REBORN brain'
curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1 && ok 'Ollama' || bad 'Ollama'
systemctl is-active --quiet asterisk && ok 'Asterisk' || bad 'Asterisk'
systemctl is-active --quiet reborn-voicebridge && ok 'VoiceBridge' || bad 'VoiceBridge'
asterisk -rx 'http show status' 2>/dev/null | grep -qi 'enabled' && ok 'ARI HTTP' || bad 'ARI HTTP'
asterisk -rx 'pjsip show endpoint twilio' >/dev/null 2>&1 && ok 'Twilio PJSIP endpoint configured' || bad 'Twilio PJSIP endpoint'

if [[ $fail -eq 0 ]]; then
  echo 'STACK_READY'
else
  echo 'STACK_NOT_READY'
fi
exit $fail

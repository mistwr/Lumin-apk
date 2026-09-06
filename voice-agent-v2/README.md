# REBORN Voice Agent V2

Objetivo: ter um segundo agente de voz, separado da app Android, a funcionar por SIP/RTP:

`PSTN -> Twilio SIP Trunk -> Asterisk -> ARI ExternalMedia -> Voice Agent -> Asterisk -> cliente`

## Base escolhida

Usamos como referência principal o projeto oficial `asterisk/AsteriskVoiceBridge` porque já demonstra exatamente ARI + External Media + voice AI em Asterisk. É um projeto de demonstração, não deve ser tratado como produção sem endurecimento.

O upstream divide o sistema em `voicebot`, `ariman` (ARI/Asterisk), `vproxy` (RTP), `deepgram` (STT/TTS), `voiceai` (LLM) e `rclocal` (ações/IVR). Isso permite-nos aproveitar a telefonia/áudio e trocar o cérebro por REBORN/Qwen. O projeto upstream requer OpenAI + Deepgram por defeito; a nossa camada local elimina essa dependência para o cérebro.

Também avaliámos:
- `asterisk/asterisk-external-media` para a camada ARI/RTP mínima;
- Pipecat para pipeline STT/LLM/TTS modular;
- LiveKit Agents para agentes realtime/WebRTC;
- Bolna para agentes de voz com SIP/Twilio.

## O que já existe nesta branch

- `bootstrap.sh` — clona/compila o AsteriskVoiceBridge no servidor.
- `docker-compose.yml` — sobe Qwen local via Ollama + API REBORN.
- `agent/server.py` — cérebro telefónico REBORN com fast-paths e fallback Qwen local.
- `agent/Dockerfile` e `agent/requirements.txt` — runtime do cérebro.
- `pull-model.sh` — descarrega o modelo local.

O endpoint do cérebro é:

```http
POST /turn
{
  "text": "Sim, diga",
  "session_id": "call-123",
  "history": []
}
```

Resposta:

```json
{
  "reply": "Perfeito. Atualmente está com que operador?",
  "route": "continue"
}
```

`route` pode ser `continue`, `handoff`, `callback` ou `end`.

## Arranque do cérebro local

```bash
cd voice-agent-v2
chmod +x pull-model.sh
./pull-model.sh
docker compose up -d --build
curl http://127.0.0.1:8080/health
```

O cérebro usa `qwen3:1.7b` por defeito. Para mudar:

```bash
OLLAMA_MODEL=qwen3:4b ./pull-model.sh
OLLAMA_MODEL=qwen3:4b docker compose up -d --build
```

## Arranque do AsteriskVoiceBridge

```bash
cd voice-agent-v2
cp .env.example .env
nano .env
chmod +x bootstrap.sh
./bootstrap.sh
```

Depois o adapter do VoiceBridge deve chamar `http://127.0.0.1:8080/turn` em vez de usar OpenAI para a decisão conversacional. Mantemos `ariman`/ARI e `vproxy`/RTP do upstream e substituímos progressivamente `voiceai` e depois STT/TTS.

## Plano de áudio

### Primeiro teste funcional

`Twilio -> Asterisk -> VoiceBridge -> Deepgram STT/TTS -> REBORN Qwen local -> VoiceBridge -> cliente`

Isto reduz a mudança inicial: usamos o áudio que já funciona no upstream e trocamos primeiro só o cérebro.

### Depois totalmente local

`Twilio -> Asterisk -> RTP -> Whisper/faster-whisper -> Qwen -> Piper/Kokoro -> RTP -> cliente`

## Segurança

Nunca colocar `TWILIO_AUTH_TOKEN`, passwords SIP, ARI ou chaves de STT/LLM/TTS no Git.

## Nota de licença

`asterisk/AsteriskVoiceBridge` usa AGPL-3.0. Se distribuirmos uma versão modificada baseada diretamente no código desse projeto, temos de cumprir os termos da licença. Este diretório contém integração/bootstrap e configuração REBORN, não uma cópia integral do código upstream.

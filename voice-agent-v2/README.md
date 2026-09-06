# REBORN Voice Agent V2

Objetivo: ter um segundo agente de voz, separado da app Android, a funcionar por SIP/RTP:

`PSTN -> Twilio SIP Trunk -> Asterisk -> ARI ExternalMedia -> Voice Agent -> Asterisk -> cliente`

## Base escolhida

Usamos como referência principal o projeto oficial `asterisk/AsteriskVoiceBridge` porque já demonstra exatamente ARI + External Media + voice AI em Asterisk. É um projeto de demonstração, não deve ser tratado como produção sem endurecimento.

Também avaliámos:
- `asterisk/asterisk-external-media` para a camada ARI/RTP mínima;
- Pipecat para pipeline STT/LLM/TTS modular;
- LiveKit Agents para agentes realtime/WebRTC;
- Bolna para agentes de voz com SIP/Twilio.

Para o nosso caso, AsteriskVoiceBridge é o ponto de partida mais direto porque já fala a linguagem do Asterisk.

## Estratégia REBORN

Não copiamos segredos para o repositório. O bootstrap clona o upstream no servidor e prepara uma camada REBORN à volta dele.

Fase 1:
1. Asterisk + Twilio trunk já configurados em `infra/asterisk`.
2. Instalar o VoiceBridge num servidor Linux.
3. Receber chamada no contexto `from-twilio`.
4. Passar a chamada para Stasis/ARI.
5. Confirmar áudio bidirecional ExternalMedia.

Fase 2:
- trocar o cérebro por Qwen/local quando a interface do provider estiver estável;
- usar STT/TTS local ou provider configurável;
- ligar hot leads ao SD Dialer/Supabase;
- transferir a chamada para humano quando necessário.

## Arranque

```bash
cd voice-agent-v2
cp .env.example .env
nano .env
chmod +x bootstrap.sh
./bootstrap.sh
```

Depois entra na pasta clonada indicada pelo script e segue o arranque do upstream.

## Segurança

Nunca colocar `TWILIO_AUTH_TOKEN`, passwords SIP, ARI ou chaves de STT/LLM/TTS no Git.

## Nota de licença

`asterisk/AsteriskVoiceBridge` usa AGPL-3.0. Se distribuirmos uma versão modificada baseada diretamente no código desse projeto, temos de cumprir os termos da licença. Este diretório contém apenas integração/bootstrap e configuração REBORN, não uma cópia do código upstream.

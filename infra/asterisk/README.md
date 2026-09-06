# REBORN AI + Asterisk + Twilio

Goal: move PSTN transport away from the fragile Samsung Text Call path and give REBORN a normal SIP/media path.

## Target architecture

PSTN <-> Twilio Elastic SIP Trunk <-> Asterisk PJSIP <-> REBORN

Asterisk also exposes ARI on port 8088 so the next step can bridge calls into REBORN AI with `Stasis()` / ExternalMedia.

## 1. Asterisk host requirements

Use a Linux host/VPS with a public IPv4/FQDN. Asterisk/PJSIP cannot be hosted on Vercel/Netlify because SIP/RTP require long-lived UDP/TCP media ports.

Open only what is needed:

- SIP UDP 5060 from trusted/Twilio sources while testing
- RTP UDP 10000-20000 from trusted/Twilio media sources
- ARI TCP 8088 only from your private network/VPN/backend; do not expose it openly

## 2. Prepare environment

```bash
cd infra/asterisk
cp .env.example .env
chmod 600 .env
```

Fill the real Twilio/Asterisk values. Never commit `.env`.

## 3. Bootstrap Twilio

Requires `curl` and `jq`.

```bash
chmod +x twilio-bootstrap.sh render-config.sh
./twilio-bootstrap.sh
```

This creates:

- Elastic SIP Trunk
- SIP Credential List + digest credential
- credential association to the trunk
- Origination URL pointing Twilio at the Asterisk host

Then associate a Twilio phone number with the new trunk. The caller ID used for termination must be a Twilio number or another caller ID verified/allowed by Twilio.

## 4. Render Asterisk config

```bash
./render-config.sh
sudo cp rendered/{pjsip.conf,extensions.conf,ari.conf,http.conf,rtp.conf} /etc/asterisk/
sudo asterisk -rx 'core reload'
```

Validate:

```bash
sudo asterisk -rx 'module show like pjsip'
sudo asterisk -rx 'pjsip show endpoints'
sudo asterisk -rx 'pjsip show endpoint twilio'
sudo asterisk -rx 'http show status'
```

## 5. First tests

From a SIP client registered as `reborn`, dial `6000`. Asterisk should answer locally and play the demo prompt. That proves the REBORN endpoint without involving Twilio.

Then dial a PT mobile number as either `+3519XXXXXXXX` or a 9-digit number. The dialplan sends it through the Twilio endpoint.

For inbound, call the Twilio number associated with the trunk. Twilio sends the INVITE to the configured Asterisk Origination URI and Asterisk rings `PJSIP/reborn`.

## 6. Important security step after the first successful call

The initial profile intentionally uses UDP/RTP because it is easier to isolate routing problems. Once the first bidirectional call works, migrate the trunk to TLS/SRTP and restrict firewall rules. Twilio supports Secure Trunking with Asterisk.

Do not expose ARI publicly. `allowed_origins=*` in `ari.conf` is only a bootstrap convenience; restrict it before production.

## 7. Next REBORN step

The `[reborn-ai]` context already calls:

```text
Stasis(reborn-ai)
```

That is the hook for an ARI controller. The intended next path is:

1. originate/answer through Asterisk;
2. create an ARI mixing bridge;
3. attach the PSTN channel;
4. create an ExternalMedia channel for PCM/RTP;
5. feed customer audio to REBORN STT/Qwen;
6. inject REBORN TTS back through the bridge.

That route gives genuine full-duplex media and removes the need to type through Samsung Text Call for VoIP calls.

import os
import re
from typing import Optional

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

app = FastAPI(title="REBORN Voice Agent V2", version="0.2.0")

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://ollama:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:1.7b")
SYSTEM_PROMPT = os.getenv(
    "SOFIA_SYSTEM_PROMPT",
    "És a Sofia, assistente virtual da MY POUPar+. Fala em português de Portugal, de forma curta, natural e profissional. Faz no máximo uma pergunta por turno. Nunca inventes preços, campanhas, cobertura ou dados de cliente. Se o cliente pedir contacto humano, confirma e termina a qualificação.",
)

ASTERISK_ARI_URL = os.getenv("ASTERISK_ARI_URL", "http://127.0.0.1:8088/ari").rstrip("/")
ASTERISK_ARI_USER = os.getenv("ASTERISK_ARI_USER", "reborn")
ASTERISK_ARI_PASSWORD = os.getenv("ASTERISK_ARI_PASSWORD", "")
ASTERISK_STASIS_APP = os.getenv("ASTERISK_STASIS_APP", "voicebot")
TWILIO_TRUNK_DOMAIN = os.getenv("TWILIO_TRUNK_DOMAIN", "").strip()
TWILIO_ENDPOINT_NAME = os.getenv("TWILIO_ENDPOINT_NAME", "twilio")
TWILIO_CALLER_ID_E164 = os.getenv("TWILIO_CALLER_ID_E164", "").strip()
GATEWAY_TOKEN = os.getenv("REBORN_GATEWAY_TOKEN", "").strip()


class TurnRequest(BaseModel):
    text: str
    session_id: Optional[str] = None
    history: list[dict] = []


class TurnResponse(BaseModel):
    reply: str
    route: str = "continue"


class CallRequest(BaseModel):
    to: str


class CallResponse(BaseModel):
    ok: bool
    channel_id: str
    to: str
    state: str


def auth_ok(authorization: Optional[str]) -> bool:
    if not GATEWAY_TOKEN:
        return False
    return authorization == f"Bearer {GATEWAY_TOKEN}"


def normalize_e164(raw: str) -> str:
    s = re.sub(r"[^0-9+]", "", raw or "")
    if s.startswith("00"):
        s = "+" + s[2:]
    if s.startswith("+"):
        if not re.fullmatch(r"\+[1-9][0-9]{7,14}", s):
            raise HTTPException(400, "invalid E.164 number")
        return s
    digits = re.sub(r"\D", "", s)
    if len(digits) == 9:
        return "+351" + digits
    raise HTTPException(400, "use a 9-digit Portuguese number or full E.164")


@app.get("/health")
def health():
    return {"ok": True, "model": OLLAMA_MODEL}


@app.get("/status")
async def status(authorization: Optional[str] = Header(default=None)):
    if not auth_ok(authorization):
        raise HTTPException(401, "unauthorized")

    ollama_ok = False
    ari_ok = False
    ollama_detail = ""
    ari_detail = ""

    async with httpx.AsyncClient(timeout=4.0) as client:
        try:
            r = await client.get(f"{OLLAMA_URL}/api/tags")
            ollama_ok = r.is_success
            ollama_detail = f"HTTP {r.status_code}"
        except Exception as exc:
            ollama_detail = str(exc)

        try:
            r = await client.get(
                f"{ASTERISK_ARI_URL}/asterisk/info",
                auth=(ASTERISK_ARI_USER, ASTERISK_ARI_PASSWORD),
            )
            ari_ok = r.is_success
            ari_detail = f"HTTP {r.status_code}"
        except Exception as exc:
            ari_detail = str(exc)

    return {
        "ok": ollama_ok and ari_ok,
        "ollama": {"ok": ollama_ok, "detail": ollama_detail, "model": OLLAMA_MODEL},
        "asterisk": {"ok": ari_ok, "detail": ari_detail, "app": ASTERISK_STASIS_APP},
        "twilio": {"configured": bool(TWILIO_TRUNK_DOMAIN and TWILIO_CALLER_ID_E164)},
    }


@app.post("/calls", response_model=CallResponse)
async def create_call(req: CallRequest, authorization: Optional[str] = Header(default=None)):
    if not auth_ok(authorization):
        raise HTTPException(401, "unauthorized")
    if not ASTERISK_ARI_PASSWORD:
        raise HTTPException(503, "Asterisk ARI password not configured")
    if not TWILIO_TRUNK_DOMAIN:
        raise HTTPException(503, "Twilio trunk domain not configured")
    if not TWILIO_CALLER_ID_E164:
        raise HTTPException(503, "Twilio caller ID not configured")

    to = normalize_e164(req.to)
    endpoint = f"PJSIP/{TWILIO_ENDPOINT_NAME}/sip:{to}@{TWILIO_TRUNK_DOMAIN}"
    params = {
        "endpoint": endpoint,
        "app": ASTERISK_STASIS_APP,
        "callerId": TWILIO_CALLER_ID_E164,
        "timeout": "45",
        "variables": '{"REBORN_ROUTE":"voice-agent-v2"}',
    }

    try:
        async with httpx.AsyncClient(timeout=12.0) as client:
            r = await client.post(
                f"{ASTERISK_ARI_URL}/channels",
                params=params,
                auth=(ASTERISK_ARI_USER, ASTERISK_ARI_PASSWORD),
            )
    except Exception as exc:
        raise HTTPException(503, f"Asterisk ARI unavailable: {exc}")

    if not r.is_success:
        raise HTTPException(r.status_code, f"ARI originate failed: {r.text[:500]}")

    data = r.json()
    return CallResponse(
        ok=True,
        channel_id=str(data.get("id", "")),
        to=to,
        state=str((data.get("state") or "originating")),
    )


@app.post("/turn", response_model=TurnResponse)
async def turn(req: TurnRequest):
    text = req.text.strip()
    if not text:
        raise HTTPException(400, "empty text")

    low = text.lower()
    if any(x in low for x in ["quero falar com uma pessoa", "consultor", "humano", "pessoa real"]):
        return TurnResponse(
            reply="Claro. Vou encaminhar o seu pedido para um consultor da MY POUPar+.",
            route="handoff",
        )

    if low in {"sim", "sim diga", "diga", "pode", "sim pode", "claro"}:
        return TurnResponse(reply="Perfeito. Atualmente está com que operador?")
    if any(x in low for x in ["não quero", "nao quero", "sem interesse", "não estou interessado", "nao estou interessado"]):
        return TurnResponse(reply="Compreendo. Obrigada pelo seu tempo e tenha um bom dia.", route="end")
    if any(x in low for x in ["mais tarde", "ligue depois", "liga depois", "agora não", "agora nao"]):
        return TurnResponse(reply="Sem problema. Fica então para mais tarde. Obrigada.", route="callback")

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for item in req.history[-8:]:
        role = item.get("role")
        content = item.get("content")
        if role in {"user", "assistant"} and isinstance(content, str):
            messages.append({"role": role, "content": content})
    messages.append({"role": "user", "content": text})

    payload = {
        "model": OLLAMA_MODEL,
        "messages": messages,
        "stream": False,
        "options": {"temperature": 0.4, "num_predict": 80},
    }

    try:
        async with httpx.AsyncClient(timeout=12.0) as client:
            r = await client.post(f"{OLLAMA_URL}/api/chat", json=payload)
            r.raise_for_status()
            data = r.json()
    except Exception as exc:
        raise HTTPException(503, f"local model unavailable: {exc}")

    reply = (data.get("message") or {}).get("content", "").strip()
    if not reply:
        raise HTTPException(503, "local model returned empty reply")

    reply = reply.replace("\n", " ").strip()
    if len(reply) > 320:
        reply = reply[:320].rsplit(" ", 1)[0] + "."
    return TurnResponse(reply=reply)

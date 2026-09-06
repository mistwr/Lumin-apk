import os
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="REBORN Voice Agent V2", version="0.1.0")

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://ollama:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:1.7b")
SYSTEM_PROMPT = os.getenv(
    "SOFIA_SYSTEM_PROMPT",
    "És a Sofia, assistente virtual da MY POUPar+. Fala em português de Portugal, de forma curta, natural e profissional. Faz no máximo uma pergunta por turno. Nunca inventes preços, campanhas, cobertura ou dados de cliente. Se o cliente pedir contacto humano, confirma e termina a qualificação.",
)


class TurnRequest(BaseModel):
    text: str
    session_id: Optional[str] = None
    history: list[dict] = []


class TurnResponse(BaseModel):
    reply: str
    route: str = "continue"


@app.get("/health")
def health():
    return {"ok": True, "model": OLLAMA_MODEL}


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

    # Fast-paths keep phone latency low and avoid waking the LLM for trivial turns.
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
        async with httpx.AsyncClient(timeout=20.0) as client:
            r = await client.post(f"{OLLAMA_URL}/api/chat", json=payload)
            r.raise_for_status()
            data = r.json()
    except Exception as exc:
        raise HTTPException(503, f"local model unavailable: {exc}")

    reply = (data.get("message") or {}).get("content", "").strip()
    if not reply:
        raise HTTPException(503, "local model returned empty reply")

    # Keep telephone turns short even if the model ignores the prompt.
    reply = reply.replace("\n", " ").strip()
    if len(reply) > 320:
        reply = reply[:320].rsplit(" ", 1)[0] + "."
    return TurnResponse(reply=reply)

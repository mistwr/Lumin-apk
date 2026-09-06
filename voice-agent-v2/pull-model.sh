#!/usr/bin/env bash
set -euo pipefail
MODEL="${OLLAMA_MODEL:-qwen3:1.7b}"
docker compose up -d ollama
until curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; do sleep 1; done
docker compose exec -T ollama ollama pull "$MODEL"
echo "Modelo pronto: $MODEL"

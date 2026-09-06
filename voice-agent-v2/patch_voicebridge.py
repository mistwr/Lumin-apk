#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/reborn/AsteriskVoiceBridge")
path = root / "voicebot" / "voicebot.go"
s = path.read_text()
orig = s

# Extra stdlib imports for local HTTP brain adapter.
s = s.replace('import (\n\t"encoding/json"', 'import (\n\t"bytes"\n\t"encoding/json"\n\t"fmt"\n\t"io"\n\t"net/http"')

# Do not instantiate OpenAI realtime controller. Deepgram STT/TTS remains; REBORN brain handles text turns.
old = '''\tchatbot, ok := voiceai.CreateOPENAIWebsocketDialogController("", OPMODE)\n\tif ok {\n\t\tvb.chatController = chatbot\n\t} else {\n\t\treturn nil, false\n\t}\n'''
s = s.replace(old, '\t// REBORN V2: OpenAI realtime controller intentionally disabled.\n\tvb.chatController = nil\n')
s = s.replace('\tvb.chatController.SetCallbacks(vb)\n', '\tif vb.chatController != nil { vb.chatController.SetCallbacks(vb) }\n')

# No OpenAI dialog lifecycle required.
old = '''\tchatend := v.chatController.DialogComplete(callid)\n\tif chatend {\n\t\tlog.Info("BOT:terminateCall", "Status", "ChatCompleteSuccess")\n\t} else {\n\t\tlog.Info("BOT:terminateCall", "Status", "ChatCompleteFailed")\n\t}\n'''
s = s.replace(old, '\tif v.chatController != nil { v.chatController.DialogComplete(callid) }\n')

old = '''\tok = v.chatController.NewDialog(vbcall.getID(), udpProxy)\n\tif !ok {\n\t\tlog.Error("BOT:HandleNewCall", "callid", ci.CallID, "error", "Failed to create dialog")\n\t\tif OPMODE == "text" {\n\t\t\tv.ttsprovider.EndCall(vbcall.getID())\n\t\t\tv.sttprovider.EndCall(vbcall.getID())\n\t\t} else if OPMODE == "hybrid" {\n\t\t\tv.ttsprovider.EndCall(vbcall.getID())\n\t\t}\n\t\tudpProxy.Stop()\n\t\treturn ok\n\t}\n'''
s = s.replace(old, '\t// REBORN V2: no remote AI dialog; STT text goes directly to local brain.\n')
s = s.replace('\t\tv.chatController.DialogComplete(vbcall.getID())\n', '\t\tif v.chatController != nil { v.chatController.DialogComplete(vbcall.getID()) }\n')

# Replace STT -> OpenAI with STT -> local REBORN brain -> Deepgram TTS.
old = '''\tif level != "passive" {\n\t\tif level == "conversationalai-vad-start" {\n\t\t\tv.ttsprovider.CancelText(callid)\n\t\t\tv.chatController.ExternalVADStart(callid, "")\n\t\t} else {\n\t\t\tv.chatController.ExternalVADText(callid, text, level)\n\t\t}\n\t}\n'''
new = '''\tif level != "passive" {\n\t\tif level == "conversationalai-vad-start" {\n\t\t\tv.ttsprovider.CancelText(callid)\n\t\t} else if strings.TrimSpace(text) != "" {\n\t\t\tgo func() {\n\t\t\t\treply, route, err := callRebornBrain(callid, text)\n\t\t\t\tif err != nil {\n\t\t\t\t\tlog.Error("REBORN:brain", "callid", callid, "error", err.Error())\n\t\t\t\t\treturn\n\t\t\t\t}\n\t\t\t\tv.SendText(callid, reply)\n\t\t\t\tif route == "end" {\n\t\t\t\t\ttime.Sleep(3 * time.Second)\n\t\t\t\t\tv.HangupCall(callid)\n\t\t\t\t}\n\t\t\t}()\n\t\t}\n\t}\n'''
if old not in s:
    raise SystemExit("expected transcript block not found; upstream changed")
s = s.replace(old, new)

# Guard optional OpenAI-only tool updates from upstream studio commander.
s = s.replace('\t\tv.chatController.UpdateDialog(dialogid, functionset, prompt)\n', '\t\tif v.chatController != nil { v.chatController.UpdateDialog(dialogid, functionset, prompt) }\n')

# Inject local brain helper before SendText.
marker = 'func (v VoiceBot) SendText(callid string, text string) {'
helper = r'''type rebornTurnRequest struct {
	Text string `json:"text"`
	SessionID string `json:"session_id"`
}

type rebornTurnResponse struct {
	Reply string `json:"reply"`
	Route string `json:"route"`
}

func callRebornBrain(callid string, text string) (string, string, error) {
	base := os.Getenv("REBORN_BRAIN_URL")
	if base == "" { base = "http://127.0.0.1:8080" }
	payload, _ := json.Marshal(rebornTurnRequest{Text: text, SessionID: callid})
	req, err := http.NewRequest("POST", strings.TrimRight(base, "/")+"/turn", bytes.NewReader(payload))
	if err != nil { return "", "", err }
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Do(req)
	if err != nil { return "", "", err }
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", "", fmt.Errorf("brain HTTP %d: %s", resp.StatusCode, string(body))
	}
	var out rebornTurnResponse
	if err := json.Unmarshal(body, &out); err != nil { return "", "", err }
	out.Reply = strings.TrimSpace(out.Reply)
	if out.Reply == "" { return "", "", fmt.Errorf("empty brain reply") }
	return out.Reply, out.Route, nil
}

'''
if marker not in s:
    raise SystemExit("SendText marker not found")
s = s.replace(marker, helper + marker, 1)

if s == orig:
    raise SystemExit("no changes made")
path.write_text(s)
print(f"Patched {path} for REBORN local brain")

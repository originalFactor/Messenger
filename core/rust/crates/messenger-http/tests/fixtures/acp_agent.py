"""Small ACP v1 peer that exercises stdio, replay and permission callbacks."""
import json
import sys

mode = sys.argv[1]
session_id = "fixture-session"
pending = None


def send(**packet):
    print(json.dumps({"jsonrpc": "2.0", **packet}), flush=True)


def update(kind, text):
    send(method="session/update", params={
        "sessionId": session_id,
        "update": {"sessionUpdate": kind, "content": {"type": "text", "text": text}},
    })


for line in sys.stdin:
    packet = json.loads(line)
    request_id = packet.get("id")
    method = packet.get("method")
    if method == "initialize":
        capabilities = {"loadSession": mode == "load"}
        if mode == "resume":
            capabilities["sessionCapabilities"] = {"resume": {}}
        send(id=request_id, result={"protocolVersion": 1, "agentCapabilities": capabilities})
    elif method == "session/new":
        send(id=request_id, result={"sessionId": session_id})
    elif method == "session/load":
        update("user_message_chunk", "replayed question")
        update("agent_message_chunk", "replayed answer")
        send(id=request_id, result={})
    elif method == "session/resume":
        send(id=request_id, result={})
    elif method == "session/prompt":
        pending = request_id
        text = packet["params"]["prompt"][0]["text"]
        if text == "wait":
            update("agent_message_chunk", "partial")
        else:
            send(id="permission", method="session/request_permission", params={
                "sessionId": session_id,
                "toolCall": {"toolCallId": "fixture-tool", "title": "Test tool"},
                "options": [{"optionId": "yes", "name": "Allow once", "kind": "allow_once"}],
            })
    elif method == "session/cancel":
        send(id=pending, result={"stopReason": "cancelled"})
    elif request_id == "permission":
        update("agent_message_chunk", packet["result"]["outcome"]["outcome"])
        send(id=pending, result={"stopReason": "end_turn"})

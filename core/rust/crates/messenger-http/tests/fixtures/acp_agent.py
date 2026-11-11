"""Small ACP v1 peer that exercises stdio, replay and permission callbacks."""
import json
import sys
import urllib.request
import urllib.parse

mode = sys.argv[1]
session_id = "fixture-session"
pending = None
permissions = "read-only"
current_mode = "plan"
mcp_url = None


def session_settings():
    return {
        "configOptions": [
            {"id": "permissions", "name": "Permissions", "type": "select", "category": "permission",
             "currentValue": permissions, "options": [
                 {"group": "permissions", "name": "Access", "options": [
                     {"value": "read-only", "name": "Read only"},
                     {"value": "workspace-write", "name": "Workspace write"},
                     {"value": "full-access", "name": "Full access"}]}]},
            {"id": "network", "name": "Network", "type": "boolean", "currentValue": False}
        ],
        "modes": {"currentModeId": current_mode, "availableModes": [
            {"id": "plan", "name": "Plan"}, {"id": "code", "name": "Code"}]}
    }



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
        capabilities = {"loadSession": mode in ("load", "settings", "settings-hang", "mcp-load")}
        if mode.startswith("mcp-") or mode == "agent-mcp":
            capabilities["mcpCapabilities"] = {"sse": True}
        if mode == "agent-mcp-http":
            capabilities["mcpCapabilities"] = {"http": True}
        if mode in ("resume", "mcp-resume"):
            capabilities["sessionCapabilities"] = {"resume": {}}
        send(id=request_id, result={"protocolVersion": 1, "agentCapabilities": capabilities, "_meta":{"steering":{"supported":True}}})
    elif method == "session/new":
        if mode.startswith("mcp-"):
            assert packet["params"]["mcpServers"][0]["name"] == "test-mcp"
            assert packet["params"]["mcpServers"][0]["env"][0]["value"] == "secret"
        if mode in ("agent-mcp", "agent-mcp-http"):
            servers = packet["params"]["mcpServers"]
            mcp_url = servers[-1]["url"] if servers else None
        send(id=request_id, result={"sessionId": session_id, **(session_settings() if mode.startswith("settings") else {})})
    elif method == "session/load":
        if mode == "mcp-load":
            assert packet["params"]["mcpServers"][0]["name"] == "test-mcp"
        update("user_message_chunk", "replayed question")
        update("agent_message_chunk", "replayed answer")
        send(id=request_id, result=session_settings() if mode.startswith("settings") else {})
    elif method == "session/set_config_option":
        params = packet["params"]
        if mode == "settings-hang" and params.get("value") == "full-access":
            continue  # Deliberately unanswered request for cancellation coverage.
        if params["configId"] == "network":
            send(id=request_id, error={"code": -32000, "message": "Network access is unavailable"})
        else:
            permissions = "workspace-write" if params["value"] == "full-access" else params["value"]
            send(id=request_id, result={"configOptions": session_settings()["configOptions"]})
    elif method == "session/set_mode":
        current_mode = packet["params"]["modeId"]
        send(method="session/update", params={"sessionId": session_id, "update": {
            "sessionUpdate": "current_mode_update", "currentModeId": current_mode}})
        send(id=request_id, result={})
    elif method == "session/resume":
        if mode == "mcp-resume":
            assert packet["params"]["mcpServers"][0]["name"] == "test-mcp"
        send(id=request_id, result={})
    elif method == "session/prompt":
        pending = request_id
        text = packet["params"]["prompt"][0]["text"]
        if mode in ("agent-mcp", "agent-mcp-http") and mcp_url is None:
            update("agent_message_chunk", "child reply")
            send(id=pending, result={"stopReason":"end_turn"})
        elif mode in ("agent-mcp", "agent-mcp-http"):
            stream = None
            endpoint = mcp_url
            if mode == "agent-mcp":
                stream = urllib.request.urlopen(mcp_url, timeout=5)
                assert stream.readline().decode().strip() == "event: endpoint"
                endpoint = urllib.parse.urljoin(mcp_url, stream.readline().decode().strip().removeprefix("data: "))
                stream.readline()
            def rpc(method, params):
                payload = json.dumps({"jsonrpc":"2.0", "id":1, "method":method, "params":params}).encode()
                req = urllib.request.Request(endpoint, data=payload, headers={"Content-Type":"application/json"})
                response = urllib.request.urlopen(req, timeout=5)
                if stream is None:
                    result = json.load(response)
                else:
                    response.close()
                    assert stream.readline().decode().strip() == "event: message"
                    result = json.loads(stream.readline().decode().strip().removeprefix("data: "))
                    stream.readline()
                return result["result"]
            rpc("initialize", {"protocolVersion":"2024-11-05", "capabilities":{}, "clientInfo":{"name":"fixture","version":"1"}})
            tools = rpc("tools/list", {})
            assert tools["tools"][0]["name"] == "messenger_agents"
            listed = rpc("tools/call", {"name":"messenger_agents", "arguments":{"action":"list"}})
            assert any(agent["id"] == "acp:fixture" for agent in json.loads(listed["content"][0]["text"])["agents"])
            result = rpc("tools/call", {"name":"messenger_agents", "arguments":{"action":"call", "agentId":"acp:fixture", "task":"child task"}})
            assert result["isError"] is False
            update("agent_message_chunk", result["content"][0]["text"])
            if stream is not None:
                stream.close()
            send(id=pending, result={"stopReason":"end_turn"})
        elif text == "tools" and mode == "settings":
            update("agent_thought_chunk", "Inspect the file")
            update("agent_message_chunk", "I will read the file. ")
            send(method="session/update", params={"sessionId": session_id, "update": {
                "sessionUpdate": "tool_call", "toolCallId": "read-file", "title": "Read file",
                "status": "in_progress", "rawInput": {"path": "sample.txt"}}})
            send(method="session/update", params={"sessionId": session_id, "update": {
                "sessionUpdate": "tool_call_update", "toolCallId": "read-file", "status": "completed",
                "content": [{"type": "content", "content": {"type": "text", "text": "file content"}}]}})
            permissions = "workspace-write"
            send(method="session/update", params={"sessionId": session_id, "update": {
                "sessionUpdate": "config_option_update", "configOptions": session_settings()["configOptions"]}})
            update("agent_message_chunk", "Read complete")
            send(id=pending, result={"stopReason": "end_turn"})
        elif text == "wait":
            update("agent_message_chunk", "partial")
        else:
            send(id="permission", method="session/request_permission", params={
                "sessionId": session_id,
                "toolCall": {"toolCallId": "fixture-tool", "title": "Test tool"},
                "options": [{"optionId": "yes", "name": "Allow once", "kind": "allow_once"}],
            })
    elif method == "_session/steering":
        assert packet["params"]["_meta"]["steering"]["idleBehavior"] == "promptRequired"
        assert packet["params"]["prompt"][1]["type"] == "image"
        send(id=request_id, result={"outcome":"injected"})
        send(method="session/update", params={"sessionId":session_id,"update":{"sessionUpdate":"usage_update","used":123,"size":1000}})
        send(method="session/update", params={"sessionId":session_id,"update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"status","description":"Status"}]}})
        send(id=pending, result={"stopReason":"end_turn"})
    elif method == "session/cancel":
        send(id=pending, result={"stopReason": "cancelled"})
    elif request_id == "permission":
        update("agent_message_chunk", packet["result"]["outcome"]["outcome"])
        send(id=pending, result={"stopReason": "end_turn"})

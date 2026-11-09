import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from textual.widgets import Static

from messenger_tui.app import Messenger
from messenger_tui.chat import Chat
from messenger_tui.core import open_core
from messenger_tui.screens import Picker
from messenger_tui.tools import execute_tool


class ToolTest(unittest.IsolatedAsyncioTestCase):
    @unittest.skipUnless(hasattr(asyncio, "eager_task_factory"), "Requires Python 3.12+")
    async def test_immediate_tool_setting_unlocks_composer_with_eager_tasks(self):
        chat = Chat(None, {"name": "Default"}, None)
        chat.project = {"id": "project", "name": "Project"}
        app = Messenger(chat, "test")
        async with app.run_test(size=(84, 24)) as pilot:
            loop = asyncio.get_running_loop()
            previous = loop.get_task_factory()
            try:
                loop.set_task_factory(asyncio.eager_task_factory)
                app.start(lambda: chat.choose_tools("read"))
            finally:
                loop.set_task_factory(previous)
            await pilot.pause()
            self.assertFalse(app.busy)
            self.assertFalse(app.composer.read_only)
            self.assertEqual(chat.tool_mode, "read")
            await pilot.press("/", *"quit", "enter")
            self.assertFalse(app.is_running)

    async def test_cancelling_tool_does_not_post_a_late_result(self):
        started = asyncio.Event()
        cancelled = asyncio.Event()
        conversation = {"id": "session", "project_id": "project", "writable": False}

        class Api:
            async def json(self, path, **kwargs):
                if path == "/conversations/session/cancel":
                    cancelled.set()
                elif path == "/conversations/session":
                    return conversation
                elif path == "/conversations/session/messages":
                    return {"items": [], "total": 0}
                else:
                    raise AssertionError(f"Unexpected API call: {path}")

            async def turn(self, path, body):
                yield {"type": "ToolCallRequested", "name": "terminal", "callId": "pending", "argumentsJson": "{}"}
                await cancelled.wait()
                yield {"type": "Cancelled"}

        async def execute(*args):
            started.set()
            await asyncio.Event().wait()

        chat = Chat(Api(), {}, {"is_enabled": True})
        chat.session = conversation
        chat.project = {"id": "project"}
        with patch("messenger_tui.chat.execute_tool", execute):
            task = asyncio.create_task(chat.turn({"content": "inspect"}, lambda: None))
            await asyncio.wait_for(started.wait(), 2)
            await chat.cancel()
            await asyncio.wait_for(task, 2)
        self.assertFalse(chat.fault)
        self.assertEqual(chat.notice, "Turn cancelled.")

    async def test_workspace_operations_and_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            project = {"workspace": directory, "owner": {"kind": "local"}}

            async def run(name, args, writable=False):
                return await execute_tool(project, writable, name, json.dumps(args))

            self.assertTrue((await run("create", {"path": "a.txt", "content": "hello"}))["isError"])
            self.assertFalse((await run("create", {"path": "a.txt", "content": "hello"}, True))["isError"])
            self.assertTrue((await run("create", {"path": "a.txt", "content": "lost"}, True))["isError"])
            self.assertFalse((await run("edit", {"path": "a.txt", "oldText": "hello", "newText": "world"}, True))["isError"])
            self.assertEqual((await run("read", {"path": "a.txt"}))["output"], "1: world")
            self.assertIn("a.txt", (await run("glob", {"pattern": "**/*.txt"}))["output"])
            self.assertIn("world", (await run("grep", {"pattern": "world"}))["output"])
            self.assertTrue((await run("terminal", {"command": "exit 7"}))["isError"])
            self.assertTrue((await execute_tool(None, False, "read", "{}"))["isError"])
            self.assertTrue((await execute_tool(project, False, "read", "[]"))["isError"])

    @unittest.skipIf(os.name == "nt", "POSIX process group test")
    async def test_terminal_cancellation_stops_command(self):
        with tempfile.TemporaryDirectory() as directory:
            project = {"workspace": directory, "owner": {"kind": "local"}}
            task = asyncio.create_task(execute_tool(project, False, "terminal", json.dumps({
                "command": "touch started; sleep 1; touch leaked",
            })))
            async with asyncio.timeout(5):
                while not (Path(directory) / "started").exists():
                    await asyncio.sleep(0.01)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            await asyncio.sleep(1.1)
            self.assertFalse((Path(directory) / "leaked").exists())

    async def test_tools_reach_provider_and_execute_with_default_agent_disabled(self):
        requests = []

        async def upstream(reader, writer):
            try:
                await reader.readline()
                headers = {}
                while (line := await reader.readline()) not in (b"\r\n", b""):
                    key, value = line.decode().split(":", 1)
                    headers[key.lower()] = value.strip()
                body = json.loads(await reader.readexactly(int(headers["content-length"])))
                requests.append(body)
                tool_reply = body["messages"][-1]["role"] == "tool"
                if body.get("tools") and not tool_reply:
                    chunk = {"choices": [{"delta": {"tool_calls": [{
                        "index": 0, "id": "call-read", "type": "function", "function": {
                            "name": "read", "arguments": json.dumps({"path": "fixture.txt"}),
                        },
                    }]}, "finish_reason": "tool_calls"}]}
                else:
                    chunk = {"choices": [{"delta": {"content": "Done"}, "finish_reason": "stop"}]}
                payload = f"data: {json.dumps(chunk)}\n\ndata: [DONE]\n\n".encode()
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + payload)
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(upstream, "127.0.0.1", 0)
        async with server:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "fixture.txt").write_text("tool fixture", encoding="utf-8")
                executable = Path(os.environ["MESSENGER_TUI_TEST_CORE"])
                async with open_core(executable, root / "chat.db") as (api, _):
                    agent = await api.json("/agents/default")
                    self.assertFalse(agent["tools_enabled"])
                    chat = Chat(api, agent, None)
                    provider = await chat.save_provider({"name": "Fake", "api_key": "test", "api_format": "chat",
                                                        "base_url": f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}/v1"})
                    await chat.choose_model(await chat.save_model({
                        "provider_id": provider["id"], "model_id": "test", "display_name": "Test", "is_enabled": True,
                    }))
                    await chat.use_workspace(root.resolve(), trusted=True)
                    app = Messenger(chat, "test")
                    async with app.run_test(size=(100, 24)) as pilot:
                        await pilot.press("/", *"tools", "enter")
                        self.assertIsInstance(app.screen, Picker)
                        await pilot.press("enter")
                        if app.operation:
                            await app.operation
                        self.assertEqual(chat.tool_mode, "read")
                        self.assertIn("Tools: read", str(app.query_one("#model", Static).content))
                    await chat.turn({"content": "read fixture"}, lambda: None)
                    self.assertFalse(chat.fault)
                    names = {tool["function"]["name"] for tool in requests[0]["tools"]}
                    self.assertEqual(names, {"terminal", "glob", "grep", "read"})
                    self.assertIn("tool fixture", requests[1]["messages"][-1]["content"])
                    self.assertEqual(chat.messages[-1]["content"], "Done")
                    selected = chat.session
                    await chat.new()
                    await chat.open(selected)
                    self.assertEqual(chat.tool_mode, "read")
                    await chat.choose_tools("off")
                    await chat.turn({"content": "no tools"}, lambda: None)
                    self.assertFalse(requests[-1].get("tools"))
                    await chat.choose_tools("write")
                    await chat.turn({"content": "with editing"}, lambda: None)
                    self.assertIn("edit", {tool["function"]["name"] for tool in requests[-1]["tools"]})


if __name__ == "__main__":
    unittest.main()

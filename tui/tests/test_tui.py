import asyncio
import json
import os
import tempfile
import unittest
from collections import Counter
from contextlib import aclosing
from pathlib import Path

from textual import events
from textual.widgets import Button, Input, Label, OptionList, Static

from messenger_tui.app import Messenger
from messenger_tui.chat import Chat, model_for
from messenger_tui.core import open_core
from messenger_tui.providers_screen import ConfirmProviderDelete, ProviderEditor, ProviderManager
from messenger_tui.screens import Picker, ProviderDialog


class TerminalTest(unittest.IsolatedAsyncioTestCase):
    async def test_completion_palette_and_provider_input(self):
        class Api:
            async def list(self, path):
                if path != "/conversations":
                    raise AssertionError(path)
                return []

        app = Messenger(Chat(Api(), {"name": "Default"}, None), "1.1.0")
        async with app.run_test(size=(84, 24)) as pilot:
            self.assertIn("No model", str(app.query_one("#model", Static).content))
            app.post_message(events.Paste("第一行\n\tsecond line"))
            await pilot.pause()
            self.assertEqual(app.composer.text, "第一行\n\tsecond line")
            self.assertFalse(app.suggestions.display)
            app.set_draft()

            await pilot.press("/")
            self.assertTrue(app.suggestions.display)
            await pilot.press("down", "down", "tab")
            self.assertEqual(app.composer.text, "/sessions")
            self.assertFalse(app.suggestions.display)
            await pilot.press("enter")
            self.assertIsInstance(app.screen, Picker)
            self.assertIn("No saved sessions", str(app.screen.query_one("#picker-hint", Static).content))
            await pilot.press("escape")

            await pilot.press("/", "r", "e")
            self.assertEqual([option.id for option in app.suggestions.options], ["retry", "regenerate"])
            await pilot.press("escape")
            self.assertEqual(app.composer.text, "/re")
            self.assertFalse(app.suggestions.display)
            await pilot.press("t")
            self.assertEqual([option.id for option in app.suggestions.options], ["retry"])
            await pilot.press("ctrl+o")
            self.assertEqual(app.composer.text, "/ret\n")
            self.assertFalse(app.suggestions.display)

            await pilot.press("ctrl+p")
            self.assertIsInstance(app.screen, Picker)
            await pilot.press(*"connect", "enter")
            self.assertIsInstance(app.screen, ProviderDialog)
            await pilot.press(*"Local", "enter")
            app.screen.query_one(Input).value = "http://localhost:1234/v1"
            await pilot.press("enter", "enter")
            self.assertIn("API key", str(app.screen.query_one(Label).content))
            self.assertTrue(app.screen.query_one(Input).password)
            await pilot.press("escape")
            self.assertEqual(app.composer.text, "/ret\n")


def delta(text, reason=None):
    body = {"choices": [{"delta": {"content": text}, "finish_reason": reason}]}
    return f"data: {json.dumps(body, ensure_ascii=False)}\n\n".encode()


class CoreTest(unittest.IsolatedAsyncioTestCase):
    async def test_manage_providers_from_terminal(self):
        executable = Path(os.environ["MESSENGER_TUI_TEST_CORE"])
        with tempfile.TemporaryDirectory() as directory:
            async with open_core(executable, Path(directory) / "providers.db") as (api, version):
                agent = await api.json("/agents/default")
                chat = Chat(api, agent, None)
                app = Messenger(chat, version)
                async with app.run_test(size=(84, 24)) as pilot:
                    await pilot.press("/", *"prov", "enter")
                    self.assertIsInstance(app.screen, ProviderManager)
                    manager = app.screen
                    await pilot.click("#add-provider")
                    self.assertIsInstance(app.screen, ProviderEditor)
                    editor = app.screen
                    editor.query_one("#name", Input).value = "Local Provider"
                    editor.query_one("#base_url", Input).value = "http://user:secret@localhost/v1"
                    self.assertTrue(editor.query_one("#api_key", Input).password)
                    await pilot.click("#save-provider")
                    self.assertIn("without embedded credentials", str(editor.query_one("#provider-result", Static).content))
                    self.assertEqual(await api.list("/providers"), [])

                    editor.query_one("#base_url", Input).value = "http://localhost:1234/v1"
                    editor.query_one("#api_key", Input).value = "test-provider-key"
                    await pilot.pause(0.3)
                    editor.query_one("#save-provider", Button).focus()
                    await pilot.press("enter")
                    await app.workers.wait_for_complete()
                    await pilot.pause()
                    self.assertIs(app.screen, manager)
                    [provider] = await api.list("/providers")
                    self.assertEqual(provider["name"], "Local Provider")
                    self.assertEqual(await api.list("/models"), [])
                    self.assertNotIn("test-provider-key", str(manager.query_one(OptionList).options[0].prompt))

                    await pilot.press(*"local", "enter")
                    editor = app.screen
                    self.assertIsInstance(editor, ProviderEditor)
                    self.assertEqual(editor.query_one("#api_key", Input).value, "")
                    editor.query_one("#name", Input).value = "Renamed Provider"
                    editor.query_one("#api_format", Input).value = "responses"
                    await pilot.click("#save-provider")
                    await app.workers.wait_for_complete()
                    await pilot.pause()
                    self.assertIs(app.screen, manager)
                    [updated] = await api.list("/providers")
                    self.assertEqual(updated, {
                        **provider, "name": "Renamed Provider", "api_format": "responses",
                        "updated_at": updated["updated_at"],
                    })

                    model = await api.json("/models", method="PUT", body={
                        "id": "model", "provider_id": provider["id"], "model_id": "upstream-model",
                        "display_name": "Model", "is_enabled": True, "created_at": 0,
                    })
                    await chat.choose_model(model)
                    await pilot.press("ctrl+a", "backspace", "enter")
                    self.assertIsInstance(app.screen, ProviderEditor)
                    await pilot.click("#delete-provider")
                    self.assertIsInstance(app.screen, ConfirmProviderDelete)
                    await pilot.press("enter")
                    self.assertIsInstance(app.screen, ProviderEditor)
                    self.assertEqual(await api.list("/providers"), [updated])
                    await pilot.pause(0.3)
                    app.screen.query_one("#delete-provider", Button).focus()
                    await pilot.press("enter")
                    await pilot.click("#confirm-delete")
                    await app.workers.wait_for_complete()
                    await pilot.pause()
                    self.assertIs(app.screen, manager)
                    self.assertEqual(await api.list("/providers"), [])
                    self.assertEqual(await api.list("/models"), [])
                    self.assertIsNone(chat.model)
                    await chat.turn({"content": "without model"}, lambda: self.fail("draft cleared"))
                    self.assertIn("model_required", chat.fault)
                    self.assertEqual(await api.list("/conversations"), [])

    async def test_local_core_stream_retry_cancel_and_restore(self):
        executable = Path(os.environ["MESSENGER_TUI_TEST_CORE"])
        requests = Counter()
        upstream_auth = []

        async def upstream(reader, writer):
            try:
                await reader.readline()
                headers = {}
                while (line := await reader.readline()) not in (b"\r\n", b""):
                    name, value = line.decode().split(":", 1)
                    headers[name.lower()] = value.strip()
                body = json.loads(await reader.readexactly(int(headers["content-length"])))
                prompt = body["messages"][-1]["content"]
                upstream_auth.append(headers.get("authorization"))
                requests[prompt] += 1
                writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                if prompt == "cancel this turn":
                    writer.write(delta("Partial reply"))
                    await writer.drain()
                    await asyncio.wait_for(reader.read(), 5)
                elif prompt == "fail once" and requests[prompt] == 1:
                    writer.write(delta("Partial error"))
                else:
                    writer.write(delta("你好，") + delta("Messenger!", "stop") + b"data: [DONE]\n\n")
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(upstream, "127.0.0.1", 0)
        async with server:
            with tempfile.TemporaryDirectory() as directory:
                database = Path(directory) / "chat.db"
                async with open_core(executable, database) as (api, _):
                    agent = await api.json("/agents/default")
                    chat = Chat(api, agent, await model_for(api, agent))
                    await chat.turn({"content": "without model"}, lambda: self.fail("draft cleared"))
                    self.assertIn("model_required", chat.fault)
                    self.assertEqual(await api.list("/conversations"), [])

                    port = server.sockets[0].getsockname()[1]
                    await chat.connect({"name": "Fake", "api_key": "test-key", "api_format": "chat",
                                        "base_url": f"http://127.0.0.1:{port}/v1", "model_id": "test"})
                    await chat.turn({"content": "第一行\n\tsecond line"}, lambda: None)
                    self.assertEqual(chat.messages[-1]["content"], "你好，Messenger!")
                    self.assertEqual(upstream_auth, ["Bearer test-key"])
                    selected = chat.session
                    await chat.turn({"content": "fail once"}, lambda: None)
                    failed = chat.messages[-1]
                    self.assertEqual(failed["status"], "error")
                    self.assertEqual(requests["fail once"], 1)
                    await chat.turn({"operation": "retry", "messageId": failed["id"]}, lambda: None)
                    self.assertEqual(chat.messages[-1]["content"], "你好，Messenger!")
                    self.assertEqual(requests["fail once"], 2)

                    path = f"/conversations/{selected['id']}"
                    async with aclosing(api.turn(path, {"content": "cancel this turn"})) as events_stream:
                        async for event in events_stream:
                            if event["type"] == "TextDelta":
                                await api.json(f"{path}/cancel", method="POST")
                        self.assertEqual(event["type"], "Cancelled")

                async with open_core(executable, database) as (api, _):
                    owner = await api.json("/agents/default")
                    self.assertEqual(owner["id"], agent["id"])
                    restored = Chat(api, owner, await model_for(api, owner))
                    await restored.open(selected)
                    self.assertEqual(restored.messages[0]["content"], "第一行\n\tsecond line")
                    self.assertNotIn(failed["id"], [row["id"] for row in restored.messages])

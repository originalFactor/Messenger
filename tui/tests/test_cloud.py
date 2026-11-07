import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path

from textual.widgets import Checkbox, Input, OptionList, Static

from messenger_tui.app import Messenger
from messenger_tui.chat import Chat
from messenger_tui.cloud import BUILTIN_PROVIDER_ID, CloudAccount, normalize_server_url
from messenger_tui.cloud_screen import CloudDialog
from messenger_tui.core import ApiError, open_core
from messenger_tui.models_screen import ModelEditor, ModelManager
from messenger_tui.providers_screen import ProviderManager
from messenger_tui.screens import Picker


class CloudTerminalTest(unittest.IsolatedAsyncioTestCase):
    async def test_cloud_dialog_saves_address_and_masks_password(self):
        class Api:
            url = "https://meowx.vip"
            user = None

            async def json(self, path, *, method="GET", body=None, timeout=None):
                match method, path:
                    case "GET", "/cloud/server-url":
                        return self.url
                    case "GET", "/cloud/me":
                        return self.user
                    case "PUT", "/cloud/server-url":
                        self.url = body["url"]
                    case "POST", "/cloud/login":
                        assert self.url == "http://localhost:4100/messenger"
                        assert body == {"email": "user@example.com", "password": "secret"}
                        self.user = {"email": body["email"], "aiApiKey": "test-cloud-key"}
                        return self.user
                    case "POST", "/cloud/builtin-models/sync":
                        assert self.user and body == {"force": False}
                        return 2
                    case "POST", "/cloud/logout":
                        self.user = None
                    case _:
                        raise AssertionError((method, path))

        api = Api()
        app = Messenger(Chat(api, {"name": "Default"}, None), "1.1.0")
        async with app.run_test(size=(84, 24)) as pilot:
            await pilot.press("/", "c", "l", "enter")
            self.assertIsInstance(app.screen, CloudDialog)
            url = app.screen.query_one("#cloud-url", Input)
            self.assertEqual(url.value, api.url)
            url.value = " http://localhost:4100/messenger/ "
            await pilot.click("#cloud-save")
            await app.workers.wait_for_complete()
            self.assertEqual(api.url, "http://localhost:4100/messenger")

            app.screen.query_one("#cloud-email", Input).value = "user@example.com"
            password = app.screen.query_one("#cloud-password", Input)
            self.assertTrue(password.password)
            password.value = "secret"
            password.focus()
            await pilot.press("enter")
            await app.workers.wait_for_complete()
            account = app.screen.query_one("#cloud-account", Static)
            self.assertIn("user@example.com", str(account.content))
            self.assertIn("Synced 2 Cloud models", str(app.screen.query_one("#cloud-result", Static).content))
            self.assertEqual(password.value, "")
            await pilot.click("#cloud-logout")
            await app.workers.wait_for_complete()
            self.assertIn("Signed out", str(account.content))

    def test_url_validation(self):
        self.assertEqual(normalize_server_url(" https://[::1]:3000/messenger// "), "https://[::1]:3000/messenger")
        for invalid in ("", "example.org", "ftp://example.org", "https://a:b@example.org",
                        "https://example.org/path?x=1", "https://example.org/#",
                        "https://example.org:90000", "https://exam\nple.org", r"https://evil\example.org"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                normalize_server_url(invalid)


class CloudCoreTest(unittest.IsolatedAsyncioTestCase):
    async def mock_cloud(self, name):
        requests = []

        async def respond(reader, writer):
            try:
                line = await reader.readline()
                if not line:
                    return
                _, path, _ = line.decode().split()
                headers = {}
                while (line := await reader.readline()) not in (b"\r\n", b""):
                    key, value = line.decode().split(":", 1)
                    headers[key.lower()] = value.strip()
                size = int(headers.get("content-length", "0"))
                body = json.loads(await reader.readexactly(size)) if size else None
                status, cookie = "404 Not Found", ""
                payload = {}
                if path.endswith("/api/auth/login"):
                    requests.append(("login", headers.get("cookie"), body))
                    if body["password"] != "secret":
                        status, payload = "401 Unauthorized", {"error": "invalid credentials"}
                    else:
                        status = "200 OK"
                        payload = {"user": {"id": name, "email": body["email"], "aiApiKey": f"key-{name}"}}
                        cookie = f"Set-Cookie: messenger_session={name}; Path=/; HttpOnly\r\n"
                elif path.endswith("/api/auth/logout"):
                    requests.append(("logout", headers.get("cookie"), None))
                    status, payload = "200 OK", {"success": True}
                elif path == "/messenger/v1/models":
                    requests.append(("models", headers.get("authorization"), None))
                    if headers.get("authorization") == f"Bearer key-{name}":
                        status = "200 OK"
                        payload = {"data": [{"id": f"model-{name}", "context_window": 4096}]}
                    else:
                        status, payload = "401 Unauthorized", {"error": "invalid AI key"}
                data = json.dumps(payload).encode()
                writer.write((
                    f"HTTP/1.1 {status}\r\n{cookie}Content-Type: application/json\r\n"
                    f"Content-Length: {len(data)}\r\nConnection: close\r\n\r\n"
                ).encode() + data)
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(respond, "127.0.0.1", 0)
        self.addAsyncCleanup(server.wait_closed)
        self.addCleanup(server.close)
        url = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}/messenger"
        return url, requests

    async def test_login_persists_and_switching_server_clears_the_session(self):
        first_url, first_requests = await self.mock_cloud("first")
        second_url, second_requests = await self.mock_cloud("second")
        executable = Path(os.environ["MESSENGER_TUI_TEST_CORE"])
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "chat.db"
            async with open_core(executable, database) as (api, _):
                account = await CloudAccount.load(api)
                with self.assertRaises(ApiError):
                    await account.login(first_url + "/", "user@example.com", "wrong")
                self.assertIsNone(account.user)
                self.assertEqual(len(first_requests), 1)
                await account.login(first_url, "user@example.com", "secret")
                self.assertEqual(account.user["id"], "first")

            async with open_core(executable, database) as (api, version):
                account = await CloudAccount.load(api)
                self.assertEqual(account.server_url, first_url)
                self.assertEqual(account.user["id"], "first")
                self.assertFalse(await account.set_server(first_url + "/"))
                self.assertEqual(await api.list("/providers"), [])
                chat = Chat(api, await api.json("/agents/default"), None)
                app = Messenger(chat, version)
                async with app.run_test(size=(84, 24)) as pilot:
                    await pilot.press("/", "m", "o", "enter")
                    self.assertIsInstance(app.screen, Picker)
                    self.assertIn("model-first", str(app.screen.query_one(OptionList).options[0].prompt))
                    await pilot.press("enter")
                    self.assertEqual(chat.model["provider_id"], BUILTIN_PROVIDER_ID)
                    [provider] = await api.list("/providers")
                    self.assertEqual(provider["base_url"], f"{first_url}/v1")
                    self.assertEqual(first_requests[-1][:2], ("models", "Bearer key-first"))

                    await pilot.press("/", "p", "r", "enter")
                    self.assertIsInstance(app.screen, ProviderManager)
                    self.assertIn("Built-in", str(app.screen.query_one(OptionList).options[0].prompt))
                    await pilot.press("enter")
                    await app.workers.wait_for_complete()
                    self.assertIsInstance(app.screen, ModelManager)
                    self.assertFalse(app.screen.query("#add-model"))
                    await pilot.press("enter")
                    self.assertIsInstance(app.screen, ModelEditor)
                    self.assertFalse(app.screen.query("#delete-model"))
                    app.screen.query_one(Checkbox).value = False
                    await pilot.click("#save-model")
                    await app.workers.wait_for_complete()
                    await pilot.click("#fetch-models")
                    await app.workers.wait_for_complete()
                    self.assertFalse(chat.model["is_enabled"])
                    await pilot.press("escape", "escape", "/", "c", "l", "enter")
                    self.assertIsInstance(app.screen, CloudDialog)
                    app.screen.query_one("#cloud-url", Input).value = second_url
                    await pilot.click("#cloud-save")
                    await app.workers.wait_for_complete()
                    self.assertIsNone(chat.model)
                    self.assertIsNone(await api.json("/cloud/me"))
                    self.assertEqual(first_requests[-1][:2], ("logout", "messenger_session=first"))
                    self.assertEqual(await api.list("/providers"), [])
                    self.assertEqual(await api.list("/models"), [])

                    app.screen.query_one("#cloud-email", Input).value = "user@example.com"
                    app.screen.query_one("#cloud-password", Input).value = "secret"
                    await pilot.click("#cloud-login")
                    await app.workers.wait_for_complete()
                    self.assertEqual(second_requests[0][:2], ("login", None))
                    self.assertEqual((await api.json("/cloud/me"))["id"], "second")
                    [model] = await api.list("/models")
                    self.assertEqual(model["model_id"], "model-second")
                    await pilot.click("#cloud-logout")
                    await app.workers.wait_for_complete()
                self.assertIsNone(await api.json("/cloud/me"))
                self.assertEqual(await api.list("/providers"), [])
                self.assertEqual(await api.json("/cloud/server-url"), second_url)

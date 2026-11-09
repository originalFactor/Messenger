"""Keyboard workflows and fixed-size rendering for the terminal UI."""

import io
import os
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

from messenger_tui.__main__ import main
from messenger_tui.app import Messenger
from messenger_tui.chat import Chat
from messenger_tui.cloud_screen import CloudDialog
from messenger_tui.models_screen import ModelEditor, ModelManager
from messenger_tui.providers_screen import ProviderEditor
from messenger_tui.screens import ConfirmDelete, FilterInput, Help, Picker, ProviderDialog
from textual import events
from textual.widgets import Input, OptionList, Static


def app():
    return Messenger(Chat(None, {"name": "Default"}, None), "test")


class InterfaceTest(unittest.IsolatedAsyncioTestCase):
    def test_dumb_terminal_fails_before_core_startup(self):
        error = io.StringIO()
        with (
            patch.dict(os.environ, {"TERM": "dumb"}),
            patch("sys.argv", ["messenger"]),
            patch("sys.stdin.isatty", return_value=True),
            patch("sys.stdout.isatty", return_value=True),
            redirect_stderr(error),
        ):
            self.assertEqual(main(), 1)
        self.assertIn("cursor-addressable", error.getvalue())
        self.assertNotIn("\x1b", error.getvalue())

    async def test_welcome_snapshot(self):
        instance = app()
        async with instance.run_test(size=(84, 24)) as pilot:
            await pilot.pause()
            frame = "\n".join(strip.text.rstrip() for strip in instance.screen._compositor.render_strips()) + "\n"
            expected = Path(__file__).parent / "snapshots" / "welcome.txt"
            self.assertEqual(frame, expected.read_text(encoding="utf-8"))

    async def test_resize_keeps_input_visible_and_restores_draft(self):
        instance = app()
        async with instance.run_test(size=(120, 32)) as pilot:
            instance.set_draft("中文 👨‍💻 é مرحبا / Hello")
            for width, height in ((120, 32), (60, 18), (40, 12)):
                await pilot.resize_terminal(width, height)
                await pilot.pause()
                self.assertLessEqual(instance.composer.region.right, width)
                self.assertLessEqual(instance.composer.region.bottom, height - 2)
                self.assertGreater(instance.history_view.region.height, 0)
            await pilot.resize_terminal(30, 8)
            await pilot.pause()
            self.assertTrue(instance.base.has_class("too-small"))
            self.assertTrue(instance.query_one("#size-warning").visible)
            await pilot.resize_terminal(84, 24)
            await pilot.pause()
            self.assertFalse(instance.base.has_class("too-small"))
            self.assertEqual(instance.composer.text, "中文 👨‍💻 é مرحبا / Hello")
            self.assertIs(instance.focused, instance.composer)

    async def test_palette_page_navigation_help_and_focus_return(self):
        instance = app()
        async with instance.run_test(size=(84, 24)) as pilot:
            instance.set_draft("Keep this draft")
            await pilot.press("ctrl+p")
            self.assertIsInstance(instance.screen, Picker)
            await pilot.press("pagedown")
            self.assertGreater(instance.screen.query_one(OptionList).highlighted, 0)
            self.assertIsInstance(instance.focused, FilterInput)
            await pilot.press("ctrl+end")
            options = instance.screen.query_one(OptionList)
            self.assertEqual(options.highlighted, options.option_count - 1)
            await pilot.press("f1")
            self.assertIsInstance(instance.screen, Help)
            await pilot.press("pagedown")
            self.assertGreater(instance.screen.query_one("Vertical").scroll_y, 0)
            await pilot.press("escape")
            self.assertIsInstance(instance.screen, Picker)
            self.assertIsInstance(instance.focused, FilterInput)
            await pilot.press("escape")
            self.assertIs(instance.focused, instance.composer)
            self.assertEqual(instance.composer.text, "Keep this draft")
            await pilot.press("tab", "shift+tab")
            self.assertEqual(instance.composer.text, "Keep this draft")

    async def test_connect_backtracks_and_preserves_password(self):
        instance = app()
        async with instance.run_test(size=(60, 18)) as pilot:
            instance.push_screen(ProviderDialog())
            await pilot.pause()
            await pilot.press(*"Local", "enter")
            instance.screen.query_one(Input).value = "http://localhost/v1"
            await pilot.press("enter", "enter")
            self.assertTrue(instance.screen.query_one(Input).password)
            instance.screen.query_one(Input).value = "private-key"
            await pilot.press("shift+tab")
            self.assertFalse(instance.screen.query_one(Input).password)
            await pilot.press("enter")
            self.assertTrue(instance.screen.query_one(Input).password)
            self.assertEqual(instance.screen.query_one(Input).value, "private-key")
            await pilot.press("escape")
            self.assertIs(instance.focused, instance.composer)

    async def test_form_enter_focus_f4_and_delete_cancel(self):
        saved = []

        class ChatStub:
            async def save_provider(self, values):
                saved.append(dict(values))
                return {**values, "id": "provider"}

        instance = app()
        async with instance.run_test(size=(60, 18)) as pilot:
            instance.push_screen(ProviderEditor(ChatStub()))
            await pilot.pause()
            await pilot.press(*"Local", "enter")
            self.assertEqual(instance.focused.id, "base_url")
            instance.focused.value = "http://localhost/v1"
            await pilot.press("enter")
            self.assertEqual(instance.focused.id, "api_format")
            await pilot.press("f4")
            await instance.workers.wait_for_complete()
            await pilot.pause()
            self.assertEqual(saved[0]["name"], "Local")
            self.assertIs(instance.focused, instance.composer)
            result = []
            instance.push_screen(ConfirmDelete("Delete this provider?", "Delete provider"), result.append)
            await pilot.pause()
            await pilot.press("enter")
            self.assertEqual(result, [False])

    async def test_signed_in_cloud_primary_action_saves_address(self):
        saved = []

        class Account:
            user = {"email": "test@example.com"}
            server_url = "http://localhost"

            async def set_server(self, url):
                saved.append(url)
                return False

        class ChatStub:
            async def refresh_model(self):
                pass

        instance = app()
        async with instance.run_test(size=(60, 18)) as pilot:
            instance.push_screen(CloudDialog(Account(), ChatStub()))
            await pilot.pause()
            await pilot.press("f4")
            await instance.workers.wait_for_complete()
            await pilot.pause()
            self.assertEqual(saved, ["http://localhost"])
            self.assertIn("Cloud address saved", str(instance.screen.query_one("#cloud-result", Static).content))

    async def test_model_manager_add_and_fetch_are_keyboard_accessible(self):
        fetched = []

        class ChatStub:
            async def provider_models(self, provider_id):
                return []

            async def fetch_models(self, provider):
                fetched.append(provider["id"])
                return "No models returned. Add one manually."

        instance = app()
        async with instance.run_test(size=(60, 18)) as pilot:
            instance.push_screen(ModelManager(ChatStub(), {"id": "provider", "name": "Local"}))
            await instance.workers.wait_for_complete()
            await pilot.pause()
            self.assertIn("F4 add", str(instance.screen.query_one("#picker-hint", Static).content))
            await pilot.press("f4")
            self.assertIsInstance(instance.screen, ModelEditor)
            await pilot.press("enter")
            self.assertEqual(instance.focused.id, "model-name")
            await pilot.press("escape", "f5")
            await instance.workers.wait_for_complete()
            await pilot.pause()
            self.assertEqual(fetched, ["provider"])
            self.assertIsInstance(instance.focused, FilterInput)

    async def test_paste_is_data_and_theme_has_monochrome_focus(self):
        with patch.dict(os.environ, {"NO_COLOR": "1"}):
            instance = app()
        async with instance.run_test(size=(84, 24)) as pilot:
            self.assertTrue(instance.no_color)
            instance.post_message(events.Paste("中文\nمرحبا 👨‍💻 é\n/quit"))
            await pilot.pause()
            self.assertTrue(instance.is_running)
            self.assertEqual(instance.composer.text, "中文\nمرحبا 👨‍💻 é\n/quit")
            instance.command("theme")
            await pilot.pause()
            self.assertEqual(instance.theme, "messenger-light")
            self.assertIs(instance.focused, instance.composer)
            instance.chat.status("Could not connect. Check the provider address.", True)
            self.assertTrue(str(instance.query_one("#status", Static).content).startswith("! "))

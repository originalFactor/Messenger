import os
import tempfile
import unittest
from pathlib import Path

from messenger_tui.agents_screen import AgentEditor, AgentManager
from messenger_tui.app import Messenger
from messenger_tui.chat import Chat, model_for
from messenger_tui.core import open_core
from messenger_tui.screens import Picker
from textual.widgets import Checkbox, Input, Static, TextArea


class AgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_edit_agent_through_core_preserves_identity_and_refreshes_model(self):
        executable = Path(os.environ["MESSENGER_TUI_TEST_CORE"])
        with tempfile.TemporaryDirectory() as directory:
            async with open_core(executable, Path(directory) / "chat.db") as (api, _):
                agent = await api.json("/agents/default")
                chat = Chat(api, agent, None)
                provider = await chat.save_provider(
                    {"name": "Local", "api_key": "", "api_format": "chat", "base_url": "http://localhost/v1"}
                )
                model = await chat.save_model(
                    {
                        "provider_id": provider["id"],
                        "model_id": "test-model",
                        "display_name": "Test model",
                        "is_enabled": True,
                    }
                )
                app = Messenger(chat, "test")
                async with app.run_test(size=(84, 24)) as pilot:
                    app.set_draft("Keep my draft")
                    app.command("agents")
                    await pilot.pause()
                    if app.operation:
                        await app.operation
                    await pilot.pause()
                    self.assertIsInstance(app.screen, AgentManager)
                    await pilot.press("f4")
                    self.assertIsInstance(app.screen, AgentEditor)
                    editor = app.screen
                    editor.query_one("#agent-name", Input).value = "My Agent"
                    editor.query_one("#agent-prompt", TextArea).load_text("First line\n中文 instructions")
                    editor.query_one("#agent-temperature", Input).value = "0.4"
                    editor.query_one("#agent-tools", Checkbox).value = True
                    editor.query_one("#agent-tools_config", Input).value = '{"terminal": false}'
                    editor.query_one("#agent-model").focus()
                    await pilot.press("enter")
                    self.assertIsInstance(app.screen, Picker)
                    await pilot.press(*"Test model", "enter")
                    await pilot.press("f4")
                    await app.workers.wait_for_complete()
                    await pilot.pause()
                    self.assertIsInstance(app.screen, AgentManager)
                    saved = await api.json(f"/agents/{agent['id']}")
                    self.assertEqual(saved["name"], "My Agent")
                    self.assertEqual(saved["system_prompt"], "First line\n中文 instructions")
                    self.assertEqual(saved["default_model_id"], model["id"])
                    self.assertEqual(saved["temperature"], 0.4)
                    self.assertTrue(saved["tools_enabled"])
                    self.assertEqual(saved["created_at"], agent["created_at"])
                    self.assertEqual(saved["is_default"], agent["is_default"])
                    self.assertEqual(chat.agent, saved)
                    self.assertEqual(chat.model["id"], model["id"])
                    self.assertEqual(sum(row["is_default"] for row in await api.list("/agents")), 1)
                    await pilot.press("escape")
                    self.assertEqual(app.composer.text, "Keep my draft")

                    inherited = await api.json(
                        "/agents",
                        method="PUT",
                        body={
                            **saved,
                            "id": "custom",
                            "name": "Inherited",
                            "is_default": False,
                            "default_model_id": None,
                            "follow_default_model": True,
                            "market_agent_id": "retained-market-id",
                            "avatar": "retained-avatar",
                        },
                    )
                    self.assertEqual((await model_for(api, inherited))["id"], model["id"])
                    chat.agent = inherited
                    await chat.save_agent({"id": "custom", "description": "Edited description"})
                    preserved = await api.json("/agents/custom")
                    self.assertEqual(preserved["avatar"], "retained-avatar")
                    self.assertEqual(preserved["market_agent_id"], "retained-market-id")
                    self.assertTrue(preserved["follow_default_model"])
                    self.assertEqual(chat.model["id"], model["id"])

    async def test_editor_validates_preserves_inheritance_and_surfaces_failures(self):
        attempts = []

        class Stub:
            async def save_agent(self, values):
                attempts.append(values)
                raise RuntimeError("Core unavailable")

            def status(self, message, error=False):
                self.notice = message

        stub = Stub()
        agent = {"id": "custom", "name": "Custom", "is_default": False, "follow_default_model": True}
        app = Messenger(Chat(None, {"name": "Default"}, None), "test")
        async with app.run_test(size=(60, 18)) as pilot:
            app.push_screen(AgentEditor(stub, agent, []))
            await pilot.pause()
            app.screen.query_one("#agent-temperature", Input).value = "nan"
            await pilot.press("f4")
            self.assertEqual(attempts, [])
            self.assertEqual(app.focused.id, "agent-temperature")
            app.screen.query_one("#agent-temperature", Input).value = ""
            app.screen.query_one("#agent-tools_config", Input).value = '{"read": 1}'
            await pilot.press("f4")
            self.assertEqual(attempts, [])
            self.assertIn("true/false", str(app.screen.query_one("#agent-result", Static).content))
            app.screen.query_one("#agent-tools_config", Input).value = ""
            app.screen.query_one("#agent-prompt", TextArea).focus()
            await pilot.press("enter", "tab")
            self.assertEqual(app.focused.id, "agent-model")
            await pilot.press("f4")
            await app.workers.wait_for_complete()
            await pilot.pause()
            self.assertTrue(attempts[0]["follow_default_model"])
            self.assertIn("Core unavailable", str(app.screen.query_one("#agent-result", Static).content))
            self.assertEqual(stub.notice, "Core unavailable")
            self.assertFalse(app.screen.pending)
            await pilot.press("escape")
            self.assertIs(app.focused, app.composer)


if __name__ == "__main__":
    unittest.main()

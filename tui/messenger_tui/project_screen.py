"""Project creation, editing, and leaving the current workspace."""

from pathlib import Path

from textual import on
from textual.app import ComposeResult
from textual.containers import Vertical
from textual.widgets import Button, Input, Label, Static

from .chat import Chat
from .screens import Dialog


class ProjectDialog(Dialog):
    DEFAULT_CSS = "ProjectDialog > Vertical { overflow-y: auto; }"

    def __init__(self, chat: Chat, directory: Path, suggested: dict | None):
        super().__init__()
        self.chat = chat
        self.directory = directory
        self.suggested = suggested
        self.editing = chat.project is not None
        self.pending = False

    def compose(self) -> ComposeResult:
        project = self.suggested or {}
        with Vertical():
            yield Label("Edit project" if self.editing else "Create or open a project", classes="dialog-title")
            yield Label("Name")
            yield Input(project.get("name", self.directory.name or str(self.directory)), id="project-name")
            yield Label("Workspace folder")
            yield Input(project.get("workspace", str(self.directory)), id="project-path")
            yield Button(
                "Save project" if self.editing else "Trust and open project", variant="primary", id="project-save"
            )
            if self.editing:
                yield Button("Leave project", id="project-leave")
            hint = (
                "Esc closes this menu."
                if self.editing
                else "Choose a folder you trust. A saved project at that folder will reopen."
            )
            yield Static(hint, id="project-result", markup=False)

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    @on(Input.Submitted)
    def submit_input(self, event: Input.Submitted) -> None:
        if event.input.id == "project-name":
            self.query_one("#project-path", Input).focus()
        else:
            self.submit(leaving=False)

    @on(Button.Pressed)
    def press_button(self, event: Button.Pressed) -> None:
        self.submit(leaving=event.button.id == "project-leave")

    def submit(self, *, leaving: bool) -> None:
        if self.pending:
            return
        self.pending = True
        self.query_one("#project-result", Static).update("Working…")
        self.set_controls_disabled(True)
        self.run_worker(self.apply(leaving))

    def set_controls_disabled(self, disabled: bool) -> None:
        for control in self.query("Input, Button"):
            control.disabled = disabled

    async def apply(self, leaving: bool) -> None:
        try:
            if leaving:
                await self.chat.leave_project()
            else:
                name = self.query_one("#project-name", Input).value.strip()
                path = self.query_one("#project-path", Input).value.strip()
                if not name or not path:
                    raise ValueError("Enter a name and an existing workspace folder.")
                directory = (self.directory / Path(path).expanduser()).resolve()
                if not directory.is_dir():
                    raise ValueError("The workspace must be an existing directory.")
                await self.chat.save_project(name, directory)
        except Exception as error:
            self.query_one("#project-result", Static).update(str(error))
            self.pending = False
            self.set_controls_disabled(False)
            self.query_one("#project-leave" if leaving else "#project-save", Button).focus()
        else:
            self.dismiss(None)

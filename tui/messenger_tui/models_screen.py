"""Discover and manage a provider's model records."""

from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.widgets import Button, Checkbox, Input, Label, OptionList, Static

from .chat import Chat
from .screens import Choice, ConfirmDelete, Dialog, FilterInput, Picker


class ModelManager(Picker):
    DEFAULT_CSS = """
    ModelManager OptionList { max-height: 8; }
    #model-list-actions { height: auto; }
    #model-list-actions Button { width: 1fr; min-width: 0; }
    """

    def __init__(self, chat: Chat, provider: dict, *, auto_fetch: bool = False):
        super().__init__(f"Models · {provider['name']}", [], "No models saved.")
        self.chat = chat
        self.provider = provider
        self.auto_fetch = auto_fetch
        self.models: dict[str, dict] = {}
        self.pending = False

    def compose(self) -> ComposeResult:
        with Vertical():
            yield from self.compose_picker()
            with Horizontal(id="model-list-actions"):
                yield Button("Add model", variant="primary", id="add-model")
                yield Button("Fetch models", id="fetch-models")
            yield Static(id="model-list-status", markup=False)

    def on_mount(self) -> None:
        self.load(fetch=self.auto_fetch)

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    def select(self, identifier: str) -> None:
        self.app.push_screen(ModelEditor(self.chat, self.provider["id"], self.models[identifier]), self.apply_change)

    @on(Button.Pressed, "#add-model")
    def add_model(self) -> None:
        self.app.push_screen(ModelEditor(self.chat, self.provider["id"]), self.apply_change)

    @on(Button.Pressed, "#fetch-models")
    def fetch_models(self) -> None:
        self.load(fetch=True)

    def load(self, *, fetch: bool) -> None:
        if self.pending:
            return
        self.pending = True
        self.set_controls_disabled(True)
        self.query_one("#model-list-status", Static).update("Fetching models…" if fetch else "Loading models…")
        self.run_worker(self.load_models(fetch))

    def set_controls_disabled(self, disabled: bool) -> None:
        for control in self.query("Input, OptionList, Button"):
            control.disabled = disabled

    async def load_models(self, fetch: bool) -> None:
        status = ""
        if fetch:
            try:
                imported = await self.chat.fetch_models(self.provider)
                status = f"Imported {imported} new models." if imported else "No new models found."
            except Exception as error:
                status = f"Fetching models failed. {error}"
        try:
            models = await self.chat.provider_models(self.provider["id"])
            self.models = {model["id"]: model for model in models}
        except Exception as error:
            status = f"{status} Loading saved models failed. {error}".strip()
        self.pending = False
        self.set_controls_disabled(False)
        self.refresh_models(status)

    def apply_change(self, change: tuple[str, dict | None] | None) -> None:
        if change is None:
            return
        identifier, model = change
        if model is None:
            del self.models[identifier]
        else:
            self.models[identifier] = model
        self.refresh_models("Model saved." if model else "Model deleted.")

    def refresh_models(self, status: str) -> None:
        self.choices = [
            Choice(model["id"], model["display_name"],
                   f"{model['model_id']} · {'Enabled' if model['is_enabled'] else 'Disabled'}")
            for model in self.models.values()
        ]
        search = self.query_one(FilterInput)
        self.filter(search.value)
        search.focus()
        self.query_one("#model-list-status", Static).update(status)


class ModelEditor(Dialog):
    DEFAULT_CSS = """
    ModelEditor > Vertical { overflow-y: auto; }
    #model-actions { height: auto; }
    #model-actions Button { width: 1fr; min-width: 0; }
    """

    def __init__(self, chat: Chat, provider_id: str, model: dict | None = None):
        super().__init__()
        self.chat = chat
        self.model = model
        self.values = dict(model or {"provider_id": provider_id})
        self.pending = False

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label(Text("Edit model" if self.model else "Add model", style="bold"))
            yield Label("Upstream model ID")
            yield Input(value=self.values.get("model_id", ""), id="model-id")
            yield Label("Display name")
            yield Input(value=self.values.get("display_name", ""), placeholder="Defaults to the model ID", id="model-name")
            yield Checkbox("Enabled", value=self.values.get("is_enabled", True), id="model-enabled")
            with Horizontal(id="model-actions"):
                yield Button("Save", variant="primary", id="save-model")
                if self.model:
                    yield Button("Delete", variant="error", id="delete-model")
            yield Static("Tab moves between fields · Esc closes", id="model-result", markup=False)

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    @on(Button.Pressed, "#save-model")
    def save(self) -> None:
        field = self.query_one("#model-id", Input)
        model_id = field.value.strip()
        if not model_id:
            self.query_one("#model-result", Static).update("Model ID is required.")
            field.focus()
            return
        self.values.update(
            model_id=model_id,
            display_name=self.query_one("#model-name", Input).value.strip() or model_id,
            is_enabled=self.query_one("#model-enabled", Checkbox).value,
        )
        self.submit(deleting=False)

    @on(Button.Pressed, "#delete-model")
    def confirm_delete(self) -> None:
        name = self.model["display_name"]
        self.app.push_screen(ConfirmDelete(f"Delete model {name}?", "Delete model"), self.delete_confirmed)

    def delete_confirmed(self, confirmed: bool | None) -> None:
        if confirmed:
            self.submit(deleting=True)

    def submit(self, *, deleting: bool) -> None:
        if self.pending:
            return
        self.pending = True
        self.set_controls_disabled(True)
        self.query_one("#model-result", Static).update("Working…")
        self.run_worker(self.perform(deleting))

    def set_controls_disabled(self, disabled: bool) -> None:
        for control in self.query("Input, Checkbox, Button"):
            control.disabled = disabled

    async def perform(self, deleting: bool) -> None:
        try:
            if deleting:
                identifier = self.model["id"]
                await self.chat.delete_model(identifier)
                change = (identifier, None)
            else:
                if not self.model or self.model["model_id"] != self.values["model_id"]:
                    rows = await self.chat.provider_models(self.values["provider_id"])
                    if any(row["model_id"] == self.values["model_id"] and row["id"] != self.values.get("id")
                           for row in rows):
                        raise ValueError("This provider already has a model with that ID.")
                saved = await self.chat.save_model(self.values)
                change = (saved["id"], saved)
        except Exception as error:
            self.query_one("#model-result", Static).update(str(error))
            self.pending = False
            self.set_controls_disabled(False)
            self.query_one("#delete-model" if deleting else "#save-model", Button).focus()
        else:
            self.dismiss(change)

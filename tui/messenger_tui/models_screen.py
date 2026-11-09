"""Discover and manage a provider's model records."""

from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Button, Checkbox, Input, Label, Static

from .chat import Chat
from .cloud import BUILTIN_PROVIDER_ID, sync_builtin_models
from .model_metadata import effective_metadata, legacy_metadata, model_overrides
from .screens import Choice, ConfirmDelete, Dialog, FilterInput, Picker

METADATA_FIELDS = (
    ("displayName", "model-name", "Display name", str),
    ("inputModalities", "model-modalities", "Input modalities", list),
    ("reasoning", "model-reasoning", "Supports reasoning", bool),
    ("reasoningEfforts", "model-efforts", "Reasoning efforts", list),
    ("contextWindow", "model-context", "Context window", int),
    ("maxOutputTokens", "model-max-tokens", "Max output tokens", int),
    ("supportsTemperature", "model-temperature", "Supports temperature", bool),
    ("supportsTopP", "model-top-p", "Supports top_p", bool),
    ("supportsPenalties", "model-penalties", "Supports penalties", bool),
)


def field_text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, list):
        return ", ".join(value) if value else "[]"
    return str(value).lower() if isinstance(value, bool) else str(value)


def parse_field(value: str, kind: type, key: str):
    if kind is bool:
        if value.lower() not in ("true", "false"):
            raise ValueError("Enter true or false, or leave blank to inherit.")
        return value.lower() == "true"
    if kind is list:
        if value == "[]":
            return []
        items = list(dict.fromkeys(part.strip() for part in value.replace("，", ",").split(",") if part.strip()))
        if len(items) > 32 or any(len(item) > 100 for item in items):
            raise ValueError("Enter up to 32 comma-separated values of 100 characters each.")
        return items
    if kind is int:
        if not value.isascii() or not value.isdecimal() or len(value) > 19:
            raise ValueError("Enter a whole token count, or leave blank to inherit.")
        count = int(value)
        if count >= 2**63 or (key == "maxOutputTokens" and not count):
            raise ValueError("Enter a valid token count. Max output tokens must be positive.")
        return count
    if len(value) > 500:
        raise ValueError("Enter a display name of at most 500 characters.")
    return value


class ModelManager(Picker):
    BINDINGS = [Binding("f5", "fetch", "Fetch models", show=False)]
    DEFAULT_CSS = """
    ModelManager OptionList { max-height: 8; }
    #model-list-actions { height: auto; }
    #model-list-actions Button { width: 1fr; min-width: 0; }
    """

    def __init__(self, chat: Chat, provider: dict, *, auto_fetch: bool = False):
        super().__init__(f"Models · {provider['name']}", [], "No models saved.")
        self.chat = chat
        self.provider = provider
        self.builtin = provider["id"] == BUILTIN_PROVIDER_ID
        self.extra_keys = "F5 sync models" if self.builtin else "F4 add model   F5 fetch models"
        self.auto_fetch = auto_fetch
        self.models: dict[str, dict] = {}
        self.pending = False

    def compose(self) -> ComposeResult:
        with Vertical():
            yield from self.compose_picker()
            with Horizontal(id="model-list-actions", classes="action-row"):
                if not self.builtin:
                    yield Button("Add model", variant="primary", id="add-model")
                yield Button("Sync Cloud models" if self.builtin else "Fetch models", id="fetch-models")
            yield Static(id="model-list-status", markup=False)

    def action_fetch(self) -> None:
        self.fetch_models()

    def on_mount(self) -> None:
        self.load(fetch=self.auto_fetch)

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    def select(self, identifier: str) -> None:
        self.app.push_screen(ModelEditor(self.chat, self.provider, self.models[identifier]), self.apply_change)

    @on(Button.Pressed, "#add-model")
    def add_model(self) -> None:
        self.app.push_screen(ModelEditor(self.chat, self.provider), self.apply_change)

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
                if self.builtin:
                    count = await sync_builtin_models(self.chat.api, force=True)
                    await self.chat.refresh_model()
                    status = f"Synced {count} Cloud models."
                else:
                    status = await self.chat.fetch_models(self.provider)
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
            Choice(
                model["id"],
                model["display_name"],
                f"{model['model_id']} · {'Enabled' if model['is_enabled'] else 'Disabled'}",
            )
            for model in self.models.values()
        ]
        search = self.query_one(FilterInput)
        self.filter(search.value)
        search.focus()
        self.query_one("#model-list-status", Static).update(status)


class ModelEditor(Dialog):
    DEFAULT_CSS = """
    ModelEditor > Vertical { height: 48; }
    #model-fields { height: 1fr; }
    #model-actions { height: auto; }
    #model-actions Button { width: 1fr; min-width: 0; }
    """

    def __init__(self, chat: Chat, provider: dict, model: dict | None = None):
        super().__init__()
        self.chat = chat
        self.provider = provider
        self.model = model
        self.builtin = provider["id"] == BUILTIN_PROVIDER_ID
        self.values = dict(model or {"provider_id": provider["id"], "model_id": ""})
        saved_metadata = self.values.get("metadata")
        self.metadata = (
            dict(saved_metadata)
            if saved_metadata is not None
            else ({} if self.builtin else legacy_metadata(self.values))
        )
        self.metadata_model_id = self.values["model_id"]
        self.overrides = {} if self.builtin else model_overrides(self.values)
        self.pending = False

    def compose(self) -> ComposeResult:
        with Vertical():
            title = "Cloud model metadata" if self.builtin else "Edit model" if self.model else "Add model"
            yield Label(Text(title, style="bold"), classes="dialog-title")
            with VerticalScroll(id="model-fields"):
                if self.builtin:
                    yield Static(f"{self.metadata_model_id} · Managed by Messenger Cloud", markup=False)
                    for key, identifier, label, _ in METADATA_FIELDS:
                        value = field_text(self.metadata.get(key))
                        yield Static(f"{label}  ·  {value or 'Not provided'}", id=identifier, markup=False)
                else:
                    yield Label("Upstream model ID")
                    yield Input(value=self.metadata_model_id, id="model-id")
                    for key, identifier, label, kind in METADATA_FIELDS:
                        yield Label(label)
                        yield Input(
                            value=field_text(self.overrides.get(key)),
                            placeholder=self.placeholder(key, kind),
                            id=identifier,
                        )
                yield Checkbox("Enabled", value=self.values.get("is_enabled", True), id="model-enabled")
            with Horizontal(id="model-actions", classes="action-row"):
                yield Button("Save", variant="primary", id="save-model")
                if not self.builtin:
                    yield Button("Fetch metadata", id="fetch-metadata")
                if self.model and not self.builtin:
                    yield Button("Delete", variant="error", id="delete-model")
            hint = (
                "Metadata comes from Messenger Cloud."
                if self.builtin
                else "Blank fields inherit · Comma-separated lists · [] clears a list"
            )
            yield Static(hint, id="model-result", markup=False)

    @on(Input.Submitted)
    def next_field(self) -> None:
        self.focus_next()

    def placeholder(self, key: str, kind: type) -> str:
        inherited = effective_metadata(self.metadata_model_id, self.metadata, {}).get(key)
        if value := field_text(inherited):
            return f"Inherit ({value})"
        return "Inherit (true or false)" if kind is bool else "Inherit"

    @on(Button.Pressed, "#fetch-metadata")
    def autofill(self) -> None:
        model_id = self.query_one("#model-id", Input).value.strip()
        if not model_id:
            self.query_one("#model-result", Static).update("Enter an upstream model ID first.")
            self.query_one("#model-id", Input).focus()
            return
        if self.begin("Fetching metadata…"):
            self.run_worker(self.load_metadata(model_id))

    async def load_metadata(self, model_id: str) -> None:
        try:
            metadata, warning = await self.chat.fetch_model_metadata(self.provider, model_id)
            if model_id != self.metadata_model_id:
                self.metadata = {}
            self.metadata.update(metadata)
            self.metadata_model_id = model_id
            for key, identifier, _, kind in METADATA_FIELDS:
                self.query_one(f"#{identifier}", Input).placeholder = self.placeholder(key, kind)
            status = "Metadata loaded. Save to apply." if metadata else "No metadata found."
            self.query_one("#model-result", Static).update(f"{status} {warning}".strip())
        except Exception as error:
            self.query_one("#model-result", Static).update(str(error))
        finally:
            self.pending = False
            self.set_controls_disabled(False)
            self.query_one("#fetch-metadata", Button).focus()

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    @on(Button.Pressed, "#save-model")
    def save(self) -> None:
        if not self.builtin:
            field = self.query_one("#model-id", Input)
            model_id = field.value.strip()
            if not model_id:
                self.query_one("#model-result", Static).update("Model ID is required.")
                field.focus()
                return
            overrides = {}
            for key, identifier, label, kind in METADATA_FIELDS:
                entry = self.query_one(f"#{identifier}", Input)
                if not (value := entry.value.strip()):
                    continue
                try:
                    overrides[key] = parse_field(value, kind, key)
                except ValueError as error:
                    self.query_one("#model-result", Static).update(f"{label} · {error}")
                    entry.focus()
                    return
            self.values.update(
                model_id=model_id,
                metadata_overrides=overrides,
                metadata=self.metadata if model_id == self.metadata_model_id else {},
            )
        self.values["is_enabled"] = self.query_one("#model-enabled", Checkbox).value
        self.submit(deleting=False)

    @on(Button.Pressed, "#delete-model")
    def confirm_delete(self) -> None:
        name = self.model["display_name"]
        self.app.push_screen(ConfirmDelete(f"Delete model {name}?", "Delete model"), self.delete_confirmed)

    def delete_confirmed(self, confirmed: bool | None) -> None:
        if confirmed:
            self.submit(deleting=True)

    def submit(self, *, deleting: bool) -> None:
        if self.begin("Working…"):
            self.run_worker(self.perform(deleting))

    def begin(self, status: str) -> bool:
        if self.pending:
            return False
        self.pending = True
        self.set_controls_disabled(True)
        self.query_one("#model-result", Static).update(status)
        return True

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
                    if any(
                        row["model_id"] == self.values["model_id"] and row["id"] != self.values.get("id")
                        for row in rows
                    ):
                        raise ValueError("This provider already has a model with that ID.")
                if self.builtin:
                    saved = await self.chat.set_model_enabled(self.model, self.values["is_enabled"])
                else:
                    saved = await self.chat.save_model(self.values)
                change = (saved["id"], saved)
        except Exception as error:
            self.query_one("#model-result", Static).update(str(error))
            self.pending = False
            self.set_controls_disabled(False)
            self.query_one("#delete-model" if deleting else "#save-model", Button).focus()
        else:
            self.dismiss(change)

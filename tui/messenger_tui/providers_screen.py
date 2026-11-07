"""Provider list, settings form, and removal confirmation."""

from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Button, Checkbox, Input, Label, Static

from .chat import Chat
from .cloud import BUILTIN_PROVIDER_ID
from .models_screen import ModelManager
from .screens import Choice, ConfirmDelete, Dialog, FilterInput, Picker, PROVIDER_FIELDS, provider_field_error


class ProviderManager(Picker):
    def __init__(self, chat: Chat, providers: list[dict]):
        super().__init__("Providers", [], "No providers configured.")
        self.chat = chat
        self.providers = {provider["id"]: provider for provider in providers}
        self.update_choices()

    def compose(self) -> ComposeResult:
        with Vertical():
            yield from self.compose_picker()
            yield Button("Add provider", variant="primary", id="add-provider")
            yield Static(id="provider-list-status", markup=False)

    def update_choices(self) -> None:
        self.choices = [
            Choice(provider["id"], provider["name"],
                   f"Built-in · {provider['base_url']}" if provider["id"] == BUILTIN_PROVIDER_ID else provider["base_url"])
            for provider in self.providers.values()
        ]

    def select(self, identifier: str) -> None:
        provider = self.providers[identifier]
        if identifier == BUILTIN_PROVIDER_ID:
            self.app.push_screen(ModelManager(self.chat, provider))
        else:
            self.app.push_screen(ProviderEditor(self.chat, provider), self.apply_change)

    @on(Button.Pressed, "#add-provider")
    def add_provider(self) -> None:
        self.app.push_screen(ProviderEditor(self.chat), self.provider_added)

    def provider_added(self, change: tuple[str, dict] | None) -> None:
        if change is not None:
            self.apply_change(change)
            self.app.push_screen(ModelManager(self.chat, change[1], auto_fetch=True))

    def apply_change(self, change: tuple[str, dict | None] | None) -> None:
        if change is None:
            return
        identifier, provider = change
        if provider is None:
            del self.providers[identifier]
            status = "Provider deleted."
        else:
            self.providers[identifier] = provider
            status = "Provider saved."
        self.update_choices()
        search = self.query_one(FilterInput)
        self.filter(search.value)
        search.focus()
        self.query_one("#provider-list-status", Static).update(status)


class ProviderEditor(Dialog):
    DEFAULT_CSS = """
    ProviderEditor > Vertical { height: 24; }
    #provider-fields { height: 1fr; }
    #provider-actions { height: auto; }
    #provider-actions Button { width: 1fr; min-width: 0; }
    """

    def __init__(self, chat: Chat, provider: dict | None = None):
        super().__init__()
        self.chat = chat
        self.provider = provider
        self.values = dict(provider or {})
        self.pending = False

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label(Text("Edit provider" if self.provider else "Add provider", style="bold"))
            with VerticalScroll(id="provider-fields"):
                for key, label, example, default in PROVIDER_FIELDS:
                    yield Label(label)
                    if key == "api_key":
                        placeholder = "Leave blank to keep saved key" if self.values.get(key) else "Optional"
                        yield Input(placeholder=placeholder, password=True, id=key)
                    else:
                        yield Input(value=self.values.get(key, default or ""), placeholder=example, id=key)
                if self.values.get("api_key"):
                    yield Checkbox("Remove saved API key", id="clear-api-key")
            with Horizontal(id="provider-actions"):
                yield Button("Save", variant="primary", id="save-provider")
                if self.provider:
                    yield Button("Manage models", id="manage-models")
                    yield Button("Delete", variant="error", id="delete-provider")
            yield Static("Tab moves between fields · Esc closes", id="provider-result", markup=False)

    def on_mount(self) -> None:
        self.query_one("#name", Input).focus()

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    @on(Button.Pressed, "#save-provider")
    def save(self) -> None:
        fields = {}
        for key, label, _, default in PROVIDER_FIELDS:
            field = self.query_one(f"#{key}", Input)
            value = field.value.strip() or default
            if error := provider_field_error(key, label, value):
                self.query_one("#provider-result", Static).update(error)
                field.focus()
                return
            fields[key] = value
        remove_key = any(control.value for control in self.query("#clear-api-key").results(Checkbox))
        if not fields["api_key"] and not remove_key and self.provider:
            fields["api_key"] = self.provider["api_key"]
        self.values.update(fields)
        self.submit(deleting=False)

    @on(Button.Pressed, "#delete-provider")
    def confirm_delete(self) -> None:
        name = self.provider["name"]
        self.app.push_screen(ConfirmDelete(f"Delete {name} and all its models?", "Delete provider"), self.delete_confirmed)

    @on(Button.Pressed, "#manage-models")
    def manage_models(self) -> None:
        self.app.push_screen(ModelManager(self.chat, self.provider))

    def delete_confirmed(self, confirmed: bool | None) -> None:
        if confirmed:
            self.submit(deleting=True)

    def submit(self, *, deleting: bool) -> None:
        if self.pending:
            return
        self.pending = True
        self.set_controls_disabled(True)
        self.query_one("#provider-result", Static).update("Working…")
        self.run_worker(self.perform(deleting))

    def set_controls_disabled(self, disabled: bool) -> None:
        for control in self.query("Input, Checkbox, Button"):
            control.disabled = disabled

    async def perform(self, deleting: bool) -> None:
        try:
            if deleting:
                identifier = self.provider["id"]
                await self.chat.delete_provider(identifier)
                change = (identifier, None)
            else:
                provider = await self.chat.save_provider(self.values)
                change = (provider["id"], provider)
        except Exception as error:
            message = str(error)
            for key in (self.values.get("api_key"), (self.provider or {}).get("api_key")):
                if key:
                    message = message.replace(key, "••••")
            self.query_one("#provider-result", Static).update(message)
            self.pending = False
            self.set_controls_disabled(False)
            self.query_one("#delete-provider" if deleting else "#save-provider", Button).focus()
        else:
            self.dismiss(change)

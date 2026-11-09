"""Native Textual dialogs for command selection and provider setup."""

from dataclasses import dataclass
from urllib.parse import urlsplit

from rich.text import Text
from textual import events, on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, OptionList, Static
from textual.widgets.option_list import Option


@dataclass(frozen=True)
class Choice:
    id: str
    label: str
    hint: str = ""
    value: dict | None = None

    def option(self) -> Option:
        text = Text(self.label)
        if self.hint:
            text.append(f"  {self.hint}", style="dim")
        return Option(text, id=self.id)


class Dialog(ModalScreen):
    BINDINGS = [
        Binding("escape", "dismiss_dialog", "Back", show=False),
        Binding("f4", "primary", "Primary action", show=False),
        Binding("pageup", "page_up", "Scroll up", show=False),
        Binding("pagedown", "page_down", "Scroll down", show=False),
    ]

    def action_dismiss_dialog(self) -> None:
        self.dismiss(None)

    def on_mount(self) -> None:
        self.set_class(self.size.width < 65 or self.size.height < 18, "narrow")
        for fields in self.query(VerticalScroll):
            fields.can_focus = False
        if self.focused is None or not self.focused.can_focus:
            self.focus_next()
        if not isinstance(self, (Picker, Help, ProviderDialog)):
            keys = (
                "Tab select   Enter confirm   Esc cancel"
                if isinstance(self, ConfirmDelete)
                else ("Tab / Shift+Tab focus   F4 primary action   Esc back")
            )
            self.query_one(Vertical).mount(Static(keys, classes="dialog-keys", markup=False))

    def on_resize(self, event: events.Resize) -> None:
        self.set_class(event.size.width < 65 or event.size.height < 18, "narrow")

    def action_primary(self) -> None:
        if getattr(self, "pending", False):
            return
        for button in self.query(Button):
            if button.variant == "primary" and button.display and not button.disabled:
                button.press()
                break

    def action_page_up(self) -> None:
        self.query_one(Vertical).scroll_page_up(animate=False)

    def action_page_down(self) -> None:
        self.query_one(Vertical).scroll_page_down(animate=False)


class TrustWorkspace(Dialog):
    def __init__(self, directory: str):
        super().__init__()
        self.directory = directory

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label("Trust this folder?", classes="dialog-title")
            yield Static(self.directory, markup=False)
            yield Static("Trust creates a project on this device and groups new chats in it.", markup=False)
            yield Button("Continue without a project", id="standalone")
            yield Button("Trust and create project", variant="primary", id="trust-workspace")

    def on_mount(self) -> None:
        self.query_one("#standalone", Button).focus()

    @on(Button.Pressed)
    def choose(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "trust-workspace")


class ConfirmDelete(Dialog):
    def __init__(self, prompt: str, button: str):
        super().__init__()
        self.prompt = prompt
        self.button = button

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Static(self.prompt, markup=False, classes="dialog-title")
            yield Button("Cancel", id="cancel-delete")
            yield Button(self.button, variant="error", id="confirm-delete")

    def on_mount(self) -> None:
        self.query_one("#cancel-delete", Button).focus()

    @on(Button.Pressed)
    def choose(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "confirm-delete")


class FilterInput(Input):
    def on_key(self, event: events.Key) -> None:
        actions = {
            "up": "cursor_up",
            "down": "cursor_down",
            "pageup": "page_up",
            "pagedown": "page_down",
            "ctrl+home": "first",
            "ctrl+end": "last",
        }
        if event.key in actions:
            event.stop()
            event.prevent_default()
            options = self.screen.query_one(OptionList)
            getattr(options, f"action_{actions[event.key]}")()


class Picker(Dialog):
    DEFAULT_CSS = """
    Picker OptionList { height: auto; max-height: 40vh; border: none; padding: 0; }
    #picker-count { height: 1; color: $text-muted; }
    #picker-hint { height: auto; color: $text-muted; }
    """

    def __init__(self, title: str, choices: list[Choice], empty: str):
        super().__init__()
        self.title_text = title
        self.choices = choices
        self.empty = empty
        self.extra_keys = ""

    def compose(self) -> ComposeResult:
        with Vertical():
            yield from self.compose_picker()

    def compose_picker(self) -> ComposeResult:
        yield Label(Text(self.title_text, style="bold"), classes="dialog-title")
        yield FilterInput(placeholder="Type to filter…")
        yield Static(id="picker-count", markup=False)
        yield OptionList()
        yield Static(id="picker-hint", markup=False)

    def on_mount(self) -> None:
        self.filter("")
        self.query_one(FilterInput).focus()

    def on_resize(self, event: events.Resize) -> None:
        options = self.query_one(OptionList)
        options.styles.height = min(options.option_count, max(1, int(event.size.height * 0.4)))

    def filter(self, text: str) -> None:
        query = text.strip().casefold()
        options = self.query_one(OptionList)
        matches = [item for item in self.choices if query in f"{item.label} {item.hint}".casefold()]
        options.set_options(item.option() for item in matches)
        options.styles.height = min(len(matches), max(1, int(self.size.height * 0.4)))
        options.highlighted = 0 if matches else None
        options.display = bool(matches)
        hint = (
            "↑/↓ select   Enter open   Esc back"
            if matches
            else "No matches. Clear the filter to see all items."
            if self.choices
            else self.empty
        )
        if self.extra_keys:
            hint += f"   {self.extra_keys}"
        self.query_one("#picker-hint", Static).update(hint)
        self.query_one("#picker-count", Static).update(f"{len(matches)} of {len(self.choices)}")

    @on(Input.Changed)
    def filter_changed(self, event: Input.Changed) -> None:
        self.filter(event.value)

    def select(self, identifier: str) -> None:
        self.dismiss(next(item for item in self.choices if item.id == identifier))

    @on(Input.Submitted)
    def submit_filter(self) -> None:
        options = self.query_one(OptionList)
        if options.highlighted is not None:
            self.select(options.get_option_at_index(options.highlighted).id)

    @on(OptionList.OptionSelected)
    def select_option(self, event: OptionList.OptionSelected) -> None:
        self.select(event.option_id)


PROVIDER_FIELDS = (
    ("name", "Provider name", "OpenAI", None),
    ("base_url", "Provider base URL", "https://api.openai.com/v1", None),
    ("api_format", "API format", "chat / responses / messages / interactions", "chat"),
    ("api_key", "API key", "Enter to leave empty", ""),
)
CONNECT_FIELDS = (
    *PROVIDER_FIELDS,
    ("model_id", "Model ID", "Use the exact ID from your provider", None),
)


def provider_field_error(key: str, label: str, value: str | None) -> str:
    if value is None:
        return f"{label} is required."
    if key == "base_url":
        try:
            url = urlsplit(value)
            valid = url.scheme in ("http", "https") and url.hostname and url.username is None and url.password is None
            _ = url.port
        except ValueError:
            valid = False
        if not valid:
            return "Enter an HTTP(S) base URL without embedded credentials."
    if key == "api_format" and value not in ("chat", "responses", "messages", "interactions"):
        return "Choose chat, responses, messages or interactions."
    return ""


class ProviderDialog(Dialog):
    BINDINGS = [Binding("shift+tab", "previous_field", "Previous field", priority=True, show=False)]

    def __init__(self, values: dict | None = None, error: str = ""):
        super().__init__()
        self.step = 0
        self.values = values or {}
        self.error = error

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label("", id="provider-label", markup=False, classes="dialog-title")
            yield Input(id="provider-input")
            yield Static(self.error or "Enter next   Shift+Tab previous   Esc back", id="provider-hint", markup=False)

    def on_mount(self) -> None:
        self.show_field()

    def show_field(self) -> None:
        key, label, example, _ = CONNECT_FIELDS[self.step]
        self.query_one(Label).update(f"Connect · {self.step + 1}/{len(CONNECT_FIELDS)} · {label}")
        field = self.query_one(Input)
        field.password = key == "api_key"
        field.placeholder = example
        field.value = self.values.get(key, "")
        field.focus()

    def action_previous_field(self) -> None:
        if self.step:
            key = CONNECT_FIELDS[self.step][0]
            self.values[key] = self.query_one(Input).value
            self.step -= 1
            self.show_field()
            self.query_one("#provider-hint", Static).update("Enter next   Shift+Tab previous   Esc back")

    @on(Input.Submitted)
    def submit_field(self, event: Input.Submitted) -> None:
        key, label, _, default = CONNECT_FIELDS[self.step]
        value = event.value.strip() or default
        error = provider_field_error(key, label, value)
        if error:
            self.query_one("#provider-hint", Static).update(error)
            return
        self.values[key] = value
        self.step += 1
        if self.step == len(CONNECT_FIELDS):
            self.dismiss(self.values)
        else:
            self.query_one("#provider-hint", Static).update("Enter next   Shift+Tab previous   Esc back")
            self.show_field()


class Help(Dialog):
    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label("Keyboard guide", classes="dialog-title")
            yield Static(
                "Conversation\n"
                "Enter          Send message\n"
                "Ctrl+O         Insert a line break (also Shift+Enter)\n"
                "PgUp / PgDn    Read history\n"
                "Ctrl+Home/End  First / latest loaded message\n"
                "Esc            Cancel the active turn\n\n"
                "Navigation\n"
                "Ctrl+P         Search all commands\n"
                "F2 / F3        New session / saved sessions\n"
                "Ctrl+X then    N new, L sessions, M models, A agents, Q quit\n"
                "/              Suggest commands; Tab fills, Enter runs\n\n"
                "Menus and forms\n"
                "Tab/Shift+Tab  Next / previous control\n"
                "Up/Down/PgDn   Navigate a filtered list\n"
                "F4             Primary action (save or add)\n"
                "F5             Fetch models in the model manager\n"
                "Shift+Tab      Previous step in /connect\n"
                "Esc            Back; return to the previous control\n\n"
                "F1 opens this guide anywhere. /theme switches appearance.\n"
                "Ctrl+C exits. Esc closes this guide.",
                markup=False,
            )

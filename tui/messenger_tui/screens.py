"""Native Textual dialogs for command selection and provider setup."""

from dataclasses import dataclass
from urllib.parse import urlsplit

from rich.text import Text
from textual import events, on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Vertical
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
    BINDINGS = [Binding("escape", "dismiss_dialog", "Close", show=False)]
    DEFAULT_CSS = """
    Dialog { align: center middle; background: $background 65%; }
    Dialog > Vertical { width: 72; max-width: 96%; height: auto; max-height: 95%;
        border: round $accent; padding: 0 1; background: $surface; }
    Dialog Label, Dialog Static { height: auto; }
    """

    def action_dismiss_dialog(self) -> None:
        self.dismiss(None)


class ConfirmDelete(Dialog):
    def __init__(self, prompt: str, button: str):
        super().__init__()
        self.prompt = prompt
        self.button = button

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Static(self.prompt, markup=False)
            yield Button("Cancel", id="cancel-delete")
            yield Button(self.button, variant="error", id="confirm-delete")

    def on_mount(self) -> None:
        self.query_one("#cancel-delete", Button).focus()

    @on(Button.Pressed)
    def choose(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "confirm-delete")


class FilterInput(Input):
    def on_key(self, event: events.Key) -> None:
        if event.key in ("up", "down"):
            event.stop()
            event.prevent_default()
            options = self.screen.query_one(OptionList)
            if event.key == "up":
                options.action_cursor_up()
            else:
                options.action_cursor_down()


class Picker(Dialog):
    DEFAULT_CSS = """
    Picker OptionList { height: auto; max-height: 12; border: none; padding: 0; }
    """

    def __init__(self, title: str, choices: list[Choice], empty: str):
        super().__init__()
        self.title_text = title
        self.choices = choices
        self.empty = empty

    def compose(self) -> ComposeResult:
        with Vertical():
            yield from self.compose_picker()

    def compose_picker(self) -> ComposeResult:
        yield Label(Text(self.title_text, style="bold"))
        yield FilterInput(placeholder="Filter…")
        yield OptionList()
        yield Static(id="picker-hint", markup=False)

    def on_mount(self) -> None:
        self.filter("")
        self.query_one(FilterInput).focus()

    def filter(self, text: str) -> None:
        query = text.strip().casefold()
        options = self.query_one(OptionList)
        matches = [item for item in self.choices if query in f"{item.label} {item.hint}".casefold()]
        options.set_options(item.option() for item in matches)
        options.highlighted = 0 if matches else None
        options.display = bool(matches)
        self.query_one("#picker-hint", Static).update(
            "↑/↓ select · Enter open · Esc close" if matches else "No matches." if self.choices else self.empty
        )

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
    def __init__(self, values: dict | None = None, error: str = ""):
        super().__init__()
        self.step = 0
        self.values = values or {}
        self.error = error

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label("", id="provider-label", markup=False)
            yield Input(id="provider-input")
            yield Static(self.error or "Enter to continue · Esc to close", id="provider-hint", markup=False)

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
            self.query_one("#provider-hint", Static).update("Enter to continue · Esc to close")
            self.show_field()


class Help(Dialog):
    def compose(self) -> ComposeResult:
        with Vertical():
            yield Static(
                "Keyboard shortcuts\n\n"
                "Enter sends · Ctrl+O or Shift+Enter inserts a line break\n"
                "Type / for suggestions · ↑/↓ select · Tab fills · Enter runs\n"
                "Ctrl+P opens the command palette\n"
                "Ctrl+X then N/L/M/A/Q opens new/sessions/models/agents/quit\n"
                "Page Up/Down scrolls history\n"
                "Esc cancels or closes · Ctrl+C exits",
                markup=False,
            )

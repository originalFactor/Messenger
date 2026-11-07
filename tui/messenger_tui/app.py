"""Messenger's Textual layout, composer, and keyboard workflows."""

import asyncio
import re
from datetime import datetime

from rich.text import Text
from textual import events, on
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import VerticalScroll
from textual.widgets import OptionList, Static, TextArea

from .chat import Chat
from .cloud import BUILTIN_PROVIDER_ID, CloudAccount, sync_builtin_models
from .cloud_screen import CloudDialog
from .providers_screen import ProviderManager
from .screens import Choice, Help, Picker, ProviderDialog

COMMANDS = (
    ("new", "New session", "n"),
    ("sessions", "Switch session", "l"),
    ("models", "Choose model", "m"),
    ("agents", "Choose agent for a new session", "a"),
    ("providers", "Manage providers", ""),
    ("connect", "Add a provider and model", ""),
    ("cloud", "Messenger Cloud account and server address", ""),
    ("retry", "Retry the last failed reply", ""),
    ("regenerate", "Regenerate the last reply", ""),
    ("help", "Keyboard shortcuts", ""),
    ("quit", "Exit Messenger", "q"),
    ("exit", "Alias for /quit", ""),
)
COMMAND_IDS = {command for command, _, _ in COMMANDS}
LEADER_KEYS = {key: command for command, _, key in COMMANDS if key}
COMPLETIONS = [Choice(command, f"/{command}", description) for command, description, _ in COMMANDS]
CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f-\x9f]")
DEFAULT_HINT = "Ctrl+P commands  ·  Ctrl+O newline  ·  PgUp/PgDn history"


def display(value: str) -> str:
    return CONTROL.sub("", value.replace("\r\n", "\n"))


class Composer(TextArea):
    def on_key(self, event: events.Key) -> None:
        if self.app.composer_key(event.key):
            event.stop()
            event.prevent_default()

    def on_paste(self, event: events.Paste) -> None:
        event.stop()
        event.text = display(event.text)


class Suggestions(OptionList, can_focus=False):
    pass


class Messenger(App):
    ENABLE_COMMAND_PALETTE = False
    BINDINGS = [
        Binding("ctrl+p", "palette", "Commands", priority=True),
        Binding("ctrl+x", "leader", "Leader", priority=True),
        Binding("ctrl+c", "quit", "Quit", priority=True),
    ]
    CSS = """
    Screen { padding: 0 1; }
    #heading { height: 1; color: $accent; text-style: bold; }
    #history { height: 1fr; padding: 0 1; }
    #transcript { height: auto; }
    #suggestions { height: auto; max-height: 5; border: none; padding: 0 1; }
    #composer { height: 5; min-height: 3; border: round $accent; }
    #status, #model, #keys { height: 1; }
    #status { color: $text-muted; }
    #status.error { color: $error; }
    #model { color: $accent; }
    #keys { color: $text-muted; }
    Screen.compact #composer { height: 3; }
    """

    def __init__(self, chat: Chat, version: str):
        super().__init__()
        self.chat = chat
        self.version = version
        self.operation: asyncio.Task | None = None
        self.cancel_task: asyncio.Task | None = None
        self.leader = False
        self.dismissed_prefix: str | None = None
        self.last_prefix: str | None = None
        self.rendered = None
        self.checked_cloud_metadata = False
        self.closing = False

    def compose(self) -> ComposeResult:
        yield Static(id="heading", markup=False)
        with VerticalScroll(id="history"):
            yield Static(id="transcript", markup=False)
        yield Suggestions(id="suggestions")
        yield Composer(id="composer", placeholder="Write a message or type /")
        yield Static(id="status", markup=False)
        yield Static(id="model", markup=False)
        yield Static(DEFAULT_HINT, id="keys", markup=False)

    def on_mount(self) -> None:
        self.base = self.screen
        self.composer = self.query_one(Composer)
        self.suggestions = self.query_one(Suggestions)
        self.history_view = self.query_one("#history", VerticalScroll)
        self.history_view.can_focus = False
        self.suggestions.display = False
        self.composer.focus()
        self.chat.update = self.refresh_view
        self.resize_layout(self.size.height)
        self.refresh_view()

    def on_resize(self, event: events.Resize) -> None:
        if hasattr(self, "base"):
            self.resize_layout(event.size.height)

    def resize_layout(self, height: int) -> None:
        self.base.set_class(height < 14, "compact")
        self.suggestions.styles.max_height = min(5, max(1, height - (8 if height < 14 else 10)))

    @property
    def busy(self) -> bool:
        return self.operation is not None

    def refresh_view(self) -> None:
        if self.closing or not self.is_running:
            return
        chat = self.chat
        title = (chat.session.get("title") or "Untitled") if chat.session else "New session"
        self.base.query_one("#heading", Static).update(display(f"MESSENGER  {self.version}   /   {title}"))
        model_enabled = bool(chat.model and chat.model["is_enabled"])
        model = chat.model["display_name"] if chat.model else "No model"
        if chat.model and not model_enabled:
            model += " (disabled)"
        self.base.query_one("#model", Static).update(display(f"{chat.agent['name']}  ·  {model}"))
        status = self.base.query_one("#status", Static)
        status.set_class(chat.notice_error, "error")
        status.update(display(chat.notice or ("Working… · Esc cancel" if self.busy else
                                              "PgUp for older messages" if chat.has_older else "")))

        rendered = (id(chat.messages), [(item["role"], item["content"]) for item in chat.live],
                    chat.fault, model_enabled)
        if rendered != self.rendered:
            self.rendered = rendered
            follow = self.history_view.is_vertical_scroll_end
            text = Text()
            messages = chat.messages + chat.live
            if not messages and not chat.fault:
                text.append("Start a conversation\n", style="bold cyan")
                text.append("Write a message below." if model_enabled else
                            "Use /providers to add a provider, or /models to choose an enabled model.", style="dim")
            for message in messages:
                content, error = message["content"], message.get("error_message")
                if not content and not error:
                    continue
                role = message["role"]
                label = {"user": "You", "assistant": "Assistant", "tool": "Tool", "system": "System"}.get(role, "Message")
                text.append(label + "\n", style="bold cyan" if role == "user" else "bold")
                if content:
                    text.append(display(content) + "\n")
                if error:
                    text.append(display(error) + "\n", style="red")
                text.append("\n")
            if chat.fault:
                text.append("Assistant\n", style="bold")
                text.append(display(chat.fault), style="red")
            self.base.query_one("#transcript", Static).update(text)
            if follow:
                self.call_after_refresh(self.history_view.scroll_end, animate=False)
        self.update_suggestions()

    def update_suggestions(self) -> None:
        prefix = self.composer.text
        if prefix != self.last_prefix:
            self.dismissed_prefix = None
        choices = [
            item for item in COMPLETIONS if item.label.startswith(prefix)
        ] if re.fullmatch(r"/[a-z]*", prefix) and prefix != self.dismissed_prefix and not self.busy else []
        if prefix != self.last_prefix or self.suggestions.display != bool(choices):
            self.suggestions.set_options(item.option() for item in choices)
            self.suggestions.highlighted = 0 if choices and prefix != "/" else None
            self.suggestions.display = bool(choices)
            self.last_prefix = prefix
        hint = DEFAULT_HINT
        if self.leader:
            hint = "Ctrl+X  n new  l sessions  m models  a agents  q quit"
        elif choices:
            hint = "↑/↓ select  ·  Tab complete  ·  Enter run  ·  Esc close"
        self.base.query_one("#keys", Static).update(hint)

    @on(TextArea.Changed, "#composer")
    def draft_changed(self) -> None:
        self.update_suggestions()

    def action_leader(self) -> None:
        if self.screen is self.base and not self.busy:
            self.leader = True
            self.update_suggestions()

    def action_palette(self) -> None:
        if self.screen is self.base and not self.busy:
            choices = [Choice(command, description, f"/{command}") for command, description, _ in COMMANDS]
            self.push_screen(Picker("Commands", choices, "No commands."), self.pick_command)

    def pick_command(self, choice: Choice | None) -> None:
        if choice:
            self.command(choice.id)

    def composer_key(self, key: str) -> bool:
        if self.leader:
            self.leader = False
            self.update_suggestions()
            if key in LEADER_KEYS:
                self.command(LEADER_KEYS[key])
                return True
        if key == "escape":
            if self.busy and (not self.cancel_task or self.cancel_task.done()):
                self.cancel_task = asyncio.create_task(self.chat.cancel())
            else:
                self.dismissed_prefix = self.composer.text
                self.update_suggestions()
            return True
        if key in ("pageup", "pagedown"):
            self.scroll_history(key)
            return True
        if self.busy:
            return key in ("enter", "ctrl+o", "shift+enter")
        if key in ("ctrl+o", "shift+enter"):
            self.composer.replace("\n", *self.composer.selection, maintain_selection_offset=False)
            return True
        self.update_suggestions()
        menu = self.suggestions
        if menu.display:
            if key in ("up", "down"):
                if key == "up":
                    menu.action_cursor_up()
                else:
                    menu.action_cursor_down()
                return True
            if key == "tab":
                option = menu.get_option_at_index(menu.highlighted or 0)
                self.set_draft(f"/{option.id}")
                self.dismissed_prefix = self.composer.text
                self.last_prefix = self.composer.text
                self.update_suggestions()
                return True
            if key == "enter" and menu.highlighted is not None:
                self.submit_command(menu.get_option_at_index(menu.highlighted).id)
                return True
        if key == "enter":
            self.submit()
            return True
        return False

    @on(OptionList.OptionSelected, "#suggestions")
    def pick_suggestion(self, event: OptionList.OptionSelected) -> None:
        if self.suggestions.display and not self.busy:
            self.submit_command(event.option_id)

    def set_draft(self, value: str = "") -> None:
        self.composer.load_text(value)
        self.composer.move_cursor((0, len(value)))

    def submit_command(self, identifier: str) -> None:
        self.set_draft()
        self.command(identifier)

    def submit(self) -> None:
        content = self.composer.text.strip()
        if content == "/":
            self.set_draft()
            self.action_palette()
        elif content.startswith("/") and content[1:] in COMMAND_IDS:
            self.submit_command(content[1:])
        elif content:
            self.history_view.scroll_end(animate=False)
            self.start(lambda: self.chat.turn({"content": content}, self.set_draft))

    def start(self, job) -> None:
        if self.busy:
            return
        self.operation = asyncio.create_task(self.perform(job))
        self.composer.read_only = True
        self.chat.status()

    async def perform(self, job) -> None:
        try:
            await job()
        except Exception as error:
            self.chat.status(str(error), True)
        finally:
            self.operation = None
            if not self.closing:
                self.composer.read_only = False
                self.refresh_view()

    async def stop_work(self) -> None:
        self.closing = True
        self.chat.update = lambda: None
        tasks = [task for task in (self.operation, self.cancel_task) if task and not task.done()]
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    def scroll_history(self, key: str) -> None:
        view = self.history_view
        if key == "pagedown":
            view.scroll_page_down(animate=False)
        elif view.scroll_y > 0 or not self.chat.has_older or self.busy:
            view.scroll_page_up(animate=False)
        else:
            self.start(self.load_older)

    async def load_older(self) -> None:
        view = self.history_view
        previous = view.max_scroll_y
        await self.chat.older()
        self.refresh_view()
        self.call_after_refresh(
            lambda: view.scroll_to(y=max(0, view.max_scroll_y - previous - view.size.height), animate=False)
        )

    def command(self, identifier: str) -> None:
        match identifier:
            case "quit" | "exit":
                self.exit()
            case "help":
                self.push_screen(Help())
            case "connect":
                self.push_screen(ProviderDialog(), self.save_provider)
            case "providers":
                self.start(self.open_providers)
            case "cloud":
                self.start(self.open_cloud)
            case "new":
                self.start(self.new_session)
            case "sessions" | "models" | "agents":
                self.start(lambda: self.show_choices(identifier))
            case "retry" | "regenerate":
                self.repeat_turn(identifier)

    async def open_cloud(self) -> None:
        account = await CloudAccount.load(self.chat.api)
        self.push_screen(CloudDialog(account, self.chat))

    async def open_providers(self) -> None:
        self.push_screen(ProviderManager(self.chat, await self.load_providers()))

    async def load_providers(self) -> list[dict]:
        api = self.chat.api
        providers = await api.list("/providers")
        if any(provider["id"] == BUILTIN_PROVIDER_ID for provider in providers):
            if self.checked_cloud_metadata:
                return providers
            self.checked_cloud_metadata = True
            models = await self.chat.provider_models(BUILTIN_PROVIDER_ID)
            if all(model.get("metadata") is not None for model in models):
                return providers
        elif not (await api.json("/cloud/me") or {}).get("aiApiKey"):
            return providers
        try:
            await sync_builtin_models(api)
            await self.chat.refresh_model()
        except Exception as error:
            self.chat.status(f"Messenger Cloud model sync failed. {error}", True)
        return await api.list("/providers")

    async def new_session(self, agent: dict | None = None) -> None:
        await self.chat.new(agent)
        self.set_draft()

    async def open_session(self, session: dict) -> None:
        await self.chat.open(session)
        self.set_draft()
        self.refresh_view()
        self.call_after_refresh(self.history_view.scroll_end, animate=False)

    def save_provider(self, values: dict | None) -> None:
        if values is not None:
            async def save() -> None:
                try:
                    await self.chat.connect(values)
                except Exception as error:
                    self.chat.status(str(error), True)
                    self.push_screen(ProviderDialog(values, str(error)), self.save_provider)

            self.start(save)

    async def show_choices(self, kind: str) -> None:
        api = self.chat.api
        if kind == "models":
            providers = {row["id"]: row["name"] for row in await self.load_providers()}
        rows = await api.list("/conversations" if kind == "sessions" else f"/{kind}")
        if kind == "sessions":
            options = [
                Choice(row["id"], row.get("title") or "Untitled",
                       datetime.fromtimestamp(row["updated_at"] / 1000).date().isoformat(), row)
                for row in rows
            ]
            title, empty, action = "Sessions", "No saved sessions.", self.open_session
        elif kind == "models":
            options = [
                Choice(row["id"], row["display_name"],
                       f"{providers.get(row['provider_id'], '')} / {row['model_id']}", row)
                for row in rows if row["is_enabled"]
            ]
            title, empty, action = "Models", "No enabled models. Manage them with /providers.", self.chat.choose_model
        else:
            options = [
                Choice(row["id"], row["name"], "Default" if row["is_default"] else "", row)
                for row in rows if row.get("role") != "title"
            ]
            title, empty, action = "Agents · starts a new session", "No chat agents.", self.new_session

        def selected(choice: Choice | None) -> None:
            if choice:
                self.start(lambda: action(choice.value))

        self.push_screen(Picker(title, options, empty), selected)

    def repeat_turn(self, operation: str) -> None:
        messages = self.chat.messages
        last_user = max((index for index, row in enumerate(messages) if row["role"] == "user"), default=-1)
        replies = [row for row in messages[last_user + 1:] if row["role"] == "assistant"]
        retry = operation == "retry"
        target = replies[-1 if retry else 0] if replies else None
        if not target or target["status"] == "sending" or (retry and target["status"] != "error"):
            self.chat.status("No failed reply to retry." if retry else "No reply to regenerate.", True)
            return
        self.history_view.scroll_end(animate=False)
        self.start(lambda: self.chat.turn({"operation": operation, "messageId": target["id"]}, lambda: None))

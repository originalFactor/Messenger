"""Agent selection and editing through the local Core API."""

import json
import math

from textual import on
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.widgets import Button, Checkbox, Input, Label, OptionList, Static, TextArea

from .chat import Chat
from .screens import Choice, Dialog, FilterInput, Picker

PARAMETERS = (
    ("temperature", "Temperature (blank: provider default)", 0, 2),
    ("top_p", "Top P (blank: provider default)", 0, 1),
    ("max_tokens", "Max output tokens (blank: provider default)", 1, 2**63 - 1),
)
INHERITANCE = (
    ("follow_default_system_prompt", "system prompt"),
    ("follow_default_model", "model"),
    ("follow_default_temperature", "temperature"),
    ("follow_default_top_p", "top P"),
    ("follow_default_max_tokens", "max output tokens"),
    ("follow_default_reasoning_effort", "reasoning effort"),
    ("tools_follow_default", "tools"),
)


class AgentManager(Picker):
    BINDINGS = [Binding("f4", "edit", "Edit selected agent", show=False)]

    def __init__(self, chat: Chat, agents: list[dict], models: list[dict]):
        self.chat = chat
        self.agents = {agent["id"]: agent for agent in agents if agent.get("role") != "title"}
        self.models = models
        super().__init__("Agents", [], "No chat agents.")
        self.extra_keys = "F4 edit selected"
        self.update_choices()

    def update_choices(self) -> None:
        self.choices = [
            Choice(agent["id"], agent["name"], "Default" if agent["is_default"] else "", agent)
            for agent in self.agents.values()
        ]

    def compose(self) -> ComposeResult:
        with Vertical():
            yield from self.compose_picker()
            yield Button("Edit selected agent", id="edit-agent")
            yield Static(id="agent-list-status", markup=False)

    @on(Button.Pressed, "#edit-agent")
    def action_edit(self) -> None:
        options = self.query_one(OptionList)
        if options.highlighted is not None:
            identifier = options.get_option_at_index(options.highlighted).id
            self.app.push_screen(AgentEditor(self.chat, self.agents[identifier], self.models), self.agent_saved)

    def agent_saved(self, saved: dict | None) -> None:
        if saved is None:
            return
        self.agents[saved["id"]] = saved
        self.update_choices()
        search = self.query_one(FilterInput)
        self.filter(search.value)
        search.focus()
        self.query_one("#agent-list-status", Static).update("Agent saved.")


class AgentEditor(Dialog):
    DEFAULT_CSS = """
    AgentEditor > Vertical { height: 28; }
    #agent-fields { height: 1fr; }
    #agent-prompt { height: 5; border: none; background: $background; }
    #agent-prompt:focus { border-left: solid $accent; }
    #agent-actions Button { width: 1fr; }
    """

    def __init__(self, chat: Chat, agent: dict, models: list[dict]):
        super().__init__()
        self.chat, self.agent, self.models = chat, agent, models
        self.model_id = agent.get("default_model_id")
        self.pending = False

    def model_label(self) -> str:
        model = next((row for row in self.models if row["id"] == self.model_id), None)
        return "Model: " + (model["display_name"] if model else self.model_id or "None")

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label("Edit default Agent" if self.agent["is_default"] else "Edit Agent", classes="dialog-title")
            with VerticalScroll(id="agent-fields"):
                yield Label("Name")
                yield Input(self.agent["name"], id="agent-name")
                yield Label("Description")
                yield Input(self.agent.get("description", ""), id="agent-description")
                yield Label("System prompt (Enter adds a line; Tab moves focus)")
                yield TextArea(self.agent.get("system_prompt", ""), id="agent-prompt", tab_behavior="focus")
                yield Button(self.model_label(), id="agent-model")
                for key, label, _, _ in PARAMETERS:
                    yield Label(label)
                    value = self.agent.get(key)
                    yield Input("" if value is None else str(value), id=f"agent-{key}")
                yield Label("Reasoning effort (blank: provider default)")
                yield Input(self.agent.get("reasoning_effort") or "", id="agent-reasoning_effort")
                yield Checkbox("Enable tools", value=self.agent.get("tools_enabled", False), id="agent-tools")
                yield Label('Per-tool settings (JSON, e.g. {"terminal": false}; blank: all enabled)')
                yield Input(self.agent.get("tools_config", ""), id="agent-tools_config")
                if not self.agent["is_default"]:
                    yield Label("Follow the default Agent (overrides the corresponding fields above)")
                    for key, label in INHERITANCE:
                        yield Checkbox(f"Follow default {label}", value=self.agent.get(key, False), id=key)
            with Horizontal(id="agent-actions", classes="action-row"):
                yield Button("Save Agent", variant="primary", id="save-agent")
            yield Static(id="agent-result", markup=False)

    def on_mount(self) -> None:
        self.query_one("#agent-name", Input).focus()

    @on(Input.Submitted)
    def next_field(self) -> None:
        self.focus_next()

    @on(Button.Pressed, "#agent-model")
    def choose_model(self) -> None:
        choices = [Choice("", "No default model", "Sending requires a model")]
        choices += [
            Choice(model["id"], model["display_name"], model["model_id"])
            for model in self.models
            if model["is_enabled"]
        ]
        self.app.push_screen(Picker("Default model", choices, "No models."), self.model_chosen)

    def model_chosen(self, choice: Choice | None) -> None:
        if choice is not None:
            self.model_id = choice.id or None
            self.query_one("#agent-model", Button).label = self.model_label()

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    @on(Button.Pressed, "#save-agent")
    def save(self) -> None:
        if self.pending:
            return
        values = {
            "id": self.agent["id"],
            "default_model_id": self.model_id,
            "system_prompt": self.query_one("#agent-prompt", TextArea).text,
            "description": self.query_one("#agent-description", Input).value,
            "tools_enabled": self.query_one("#agent-tools", Checkbox).value,
        }
        field = self.query_one("#agent-name", Input)
        try:
            values["name"] = field.value.strip()
            if not values["name"]:
                raise ValueError("Name is required.")
            for key, label, minimum, maximum in PARAMETERS:
                field = self.query_one(f"#agent-{key}", Input)
                raw = field.value.strip()
                try:
                    value = (int(raw) if key == "max_tokens" else float(raw)) if raw else None
                except ValueError:
                    raise ValueError(f"{label.split(' (')[0]} must be a valid number.") from None
                if value is not None and (
                    not minimum <= value <= maximum or (key != "max_tokens" and not math.isfinite(value))
                ):
                    raise ValueError(f"{label.split(' (')[0]} must be between {minimum} and {maximum}.")
                values[key] = value
            values["reasoning_effort"] = self.query_one("#agent-reasoning_effort", Input).value.strip() or None
            field = self.query_one("#agent-tools_config", Input)
            raw = field.value.strip()
            config = json.loads(raw) if raw else {}
            if not isinstance(config, dict) or any(type(value) is not bool for value in config.values()):
                raise ValueError("Per-tool settings must be a JSON object with true/false values.")
            values["tools_config"] = json.dumps(config) if raw else ""
            if not self.agent["is_default"]:
                values.update({key: self.query_one(f"#{key}", Checkbox).value for key, _ in INHERITANCE})
        except ValueError as error:
            self.query_one("#agent-result", Static).update(f"! {error}")
            field.focus()
            return
        self.pending = True
        for control in self.query("Input, TextArea, Checkbox, Button"):
            control.disabled = True
        self.query_one("#agent-result", Static).update("Saving Agent…")
        self.run_worker(self.persist(values))

    async def persist(self, values: dict) -> None:
        try:
            saved = await self.chat.save_agent(values)
        except Exception as error:
            self.pending = False
            for control in self.query("Input, TextArea, Checkbox, Button"):
                control.disabled = False
            self.query_one("#agent-result", Static).update(f"! {error}")
            self.chat.status(str(error), True)
            self.query_one("#save-agent", Button).focus()
        else:
            self.dismiss(saved)

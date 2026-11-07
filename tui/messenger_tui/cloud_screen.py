"""The Messenger Cloud account dialog."""

from rich.text import Text
from textual import on
from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.widgets import Button, Input, Label, Static

from .cloud import CloudAccount
from .screens import Dialog


class CloudDialog(Dialog):
    DEFAULT_CSS = """
    CloudDialog > Vertical { overflow-y: auto; }
    #cloud-credentials { height: auto; }
    #cloud-actions { height: auto; }
    #cloud-actions Button { width: 1fr; min-width: 0; }
    """

    def __init__(self, account: CloudAccount):
        super().__init__()
        self.account = account
        self.pending = False

    def compose(self) -> ComposeResult:
        with Vertical():
            yield Label(Text("Messenger Cloud", style="bold"))
            yield Static(id="cloud-account", markup=False)
            yield Label("Cloud base URL")
            yield Input(value=self.account.server_url, id="cloud-url")
            with Vertical(id="cloud-credentials"):
                yield Input(placeholder="Email", id="cloud-email")
                yield Input(placeholder="Password", password=True, id="cloud-password")
            with Horizontal(id="cloud-actions"):
                yield Button("Sign in", variant="primary", id="cloud-login")
                yield Button("Save address", id="cloud-save")
                yield Button("Sign out", id="cloud-logout")
            yield Static("Tab moves between fields · Esc closes", id="cloud-result", markup=False)

    def on_mount(self) -> None:
        self.update_account()
        self.query_one("#cloud-url" if self.account.user else "#cloud-email", Input).focus()

    def update_account(self) -> None:
        user = self.account.user
        account = "Signed out"
        if user:
            name = user.get("email") or user.get("displayName") or "Messenger Cloud account"
            account = f"Signed in as {name}\nSaving a different address signs out this account."
        self.query_one("#cloud-account", Static).update(Text(account))
        self.query_one("#cloud-credentials").display = user is None
        self.query_one("#cloud-login", Button).display = user is None
        self.query_one("#cloud-logout", Button).display = user is not None

    def action_dismiss_dialog(self) -> None:
        if not self.pending:
            super().action_dismiss_dialog()

    @on(Button.Pressed)
    def button_pressed(self, event: Button.Pressed) -> None:
        self.submit(event.button.id)

    @on(Input.Submitted)
    def input_submitted(self, event: Input.Submitted) -> None:
        match event.input.id:
            case "cloud-password":
                self.submit("cloud-login")
            case "cloud-email":
                self.query_one("#cloud-password", Input).focus()
            case "cloud-url":
                if self.account.user:
                    self.submit("cloud-save")
                else:
                    self.query_one("#cloud-email", Input).focus()

    def submit(self, action: str) -> None:
        if self.pending or (action == "cloud-login" and self.account.user):
            return
        self.pending = True
        self.set_controls_disabled(True)
        self.query_one("#cloud-result", Static).update("Working…")
        self.run_worker(self.perform(action))

    def set_controls_disabled(self, disabled: bool) -> None:
        for control in self.query("Input, Button"):
            control.disabled = disabled

    async def perform(self, action: str) -> None:
        url = self.query_one("#cloud-url", Input)
        password = self.query_one("#cloud-password", Input)
        submitted_password = password.value if action == "cloud-login" else ""
        try:
            if action == "cloud-login":
                email = self.query_one("#cloud-email", Input)
                await self.account.login(url.value, email.value, submitted_password)
                result = "Signed in to Messenger Cloud."
            elif action == "cloud-save":
                signed_out = await self.account.set_server(url.value)
                result = "Cloud address saved. Signed out of the previous server." if signed_out else "Cloud address saved."
            else:
                await self.account.logout()
                result = "Signed out of Messenger Cloud."
            url.value = self.account.server_url
        except Exception as error:
            result = str(error)
            if submitted_password:
                result = result.replace(submitted_password, "••••")
        finally:
            if action == "cloud-login":
                password.value = ""
            self.pending = False
            self.set_controls_disabled(False)
            self.update_account()
        self.query_one("#cloud-result", Static).update(Text(result))
        focus = "#cloud-password" if action == "cloud-login" and not self.account.user else "#cloud-url"
        self.query_one(focus, Input).focus()

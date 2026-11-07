"""Conversation state and turn orchestration for the Textual client."""

import asyncio
import time
from contextlib import aclosing
from uuid import uuid4

from .core import ApiError, CoreApi, entity

HISTORY_LIMIT = 100


def now_ms() -> int:
    return time.time_ns() // 1_000_000


async def model_for(api: CoreApi, agent: dict, conversation: dict | None = None):
    identifier = (conversation or {}).get("override_model_id") or agent.get("default_model_id")
    if identifier:
        try:
            return await api.json(entity("models", identifier))
        except ApiError as error:
            if error.code != "not_found":
                raise
    return None


class Chat:
    def __init__(self, api: CoreApi, agent: dict, model: dict | None):
        self.api = api
        self.agent = agent
        self.model = model
        self.session = None
        self.messages: list[dict] = []
        self.has_older = False
        self.live: list[dict] = []
        self.fault = ""
        self.notice = ""
        self.notice_error = False
        self.update = lambda: None
        self.turn_task: asyncio.Task | None = None
        self.turn_path: str | None = None
        self.accepted = False

    def status(self, text: str = "", error: bool = False) -> None:
        self.notice = text
        self.notice_error = error
        self.update()

    async def new(self, agent: dict | None = None) -> None:
        owner = agent or self.agent
        self.model = await model_for(self.api, owner)
        self.agent = owner
        self.session = None
        self.messages = []
        self.has_older = False
        self.live = []
        self.fault = ""

    async def history(self, path: str):
        return await asyncio.gather(
            self.api.json(path), self.api.json(f"{path}/messages", params={"limit": HISTORY_LIMIT})
        )

    def apply_history(self, conversation: dict, history: dict) -> None:
        self.session = conversation
        self.messages = history["items"]
        self.has_older = len(self.messages) < history["total"]
        self.live = []

    async def open(self, selected: dict) -> None:
        conversation, history = await self.history(entity("conversations", selected["id"]))
        owner = await self.api.json(entity("agents", conversation["agent_id"]))
        self.model = await model_for(self.api, owner, conversation)
        self.agent = owner
        self.apply_history(conversation, history)
        self.fault = ""

    async def older(self) -> None:
        if not self.has_older or not self.messages or not self.session:
            return
        path = entity("conversations", self.session["id"])
        page = await self.api.json(
            f"{path}/messages", params={"limit": HISTORY_LIMIT, "beforeId": self.messages[0]["id"]}
        )
        self.messages = page["items"] + self.messages
        self.has_older = bool(page["items"]) and len(self.messages) < page["total"]

    async def choose_model(self, model: dict) -> None:
        if self.session:
            path = entity("conversations", self.session["id"])
            current = await self.api.json(path)
            self.session = await self.api.json(path, method="PUT", body={
                **current, "provider_id": model["provider_id"],
                "override_model_id": model["id"], "updated_at": now_ms(),
            })
        else:
            path = entity("agents", self.agent["id"])
            current = await self.api.json(path)
            self.agent = await self.api.json(path, method="PUT", body={
                **current, "default_model_id": model["id"], "updated_at": now_ms(),
            })
        self.model = model
        self.status(f"Model set to {model['display_name']}.")

    async def connect(self, values: dict) -> None:
        now = values.setdefault("created_at", now_ms())
        provider_id = values.setdefault("provider_id", str(uuid4()))
        model_id = values.setdefault("record_id", str(uuid4()))
        await self.api.json("/providers", method="PUT", body={
            "id": provider_id, "name": values["name"], "base_url": values["base_url"],
            "api_format": values["api_format"], "api_key": values["api_key"],
            "created_at": now, "updated_at": now,
        })
        model = await self.api.json("/models", method="PUT", body={
            "id": model_id, "provider_id": provider_id, "model_id": values["model_id"],
            "display_name": values["model_id"], "is_enabled": True, "created_at": now,
        })
        await self.choose_model(model)

    async def create_session(self, content: str) -> dict:
        now = now_ms()
        return await self.api.json("/conversations", method="PUT", body={
            "id": str(uuid4()), "title": content.splitlines()[0][:64], "provider_id": self.model["provider_id"],
            "agent_id": self.agent["id"], "created_at": now, "updated_at": now,
        })

    def accept(self, body: dict) -> None:
        self.accepted = True
        if body.get("operation") == "retry":
            self.messages = [row for row in self.messages if row["id"] != body["messageId"]]
        elif body.get("operation") == "regenerate":
            for index, row in enumerate(self.messages):
                if row["id"] == body["messageId"]:
                    self.messages = self.messages[:index] + [
                        item for item in self.messages[index:] if item["role"] not in ("assistant", "tool")
                    ]
                    break
        if content := body.get("content"):
            self.live.append({"role": "user", "content": content})
        self.live.append({"role": "assistant", "content": ""})

    async def turn(self, body: dict, on_accepted) -> None:
        self.turn_task = asyncio.current_task()
        self.accepted = False
        self.fault = ""
        self.live = []
        last_round = None
        failure = None
        refresh_failure = None
        try:
            if not self.model:
                raise ApiError("model_required", "Choose a model with /models or add one with /connect.")
            if not self.session:
                self.session = await self.create_session(body["content"])
            self.turn_path = entity("conversations", self.session["id"])
            self.status("Waiting for the model…")
            async with aclosing(self.api.turn(self.turn_path, body)) as events:
                async for event in events:
                    if not self.accepted:
                        self.accept(body)
                        on_accepted()
                        self.update()
                    match event["type"]:
                        case "TextDelta":
                            if last_round is not None and last_round != event["round"]:
                                self.live[-1]["content"] += "\n\n"
                            self.live[-1]["content"] += event["text"]
                            last_round = event["round"]
                            self.update()
                        case "ToolCallStarted":
                            self.status(f"Tool · {event['name']}")
                        case "ToolCallRequested":
                            path = entity("turns/tools", event["callId"])
                            await self.api.json(f"{path}/result", method="POST", body={
                                "output": "Local tool execution is unavailable in the terminal client.",
                                "isError": True,
                            })
                        case "ToolCallFinished":
                            failed = event["isError"]
                            self.status(f"Tool failed · {event['name']}" if failed else "Waiting for the model…", failed)
                        case "Error":
                            failure = ApiError(event["code"], event["message"])
                        case "Cancelled":
                            self.status("Turn cancelled.")
                        case "Finished":
                            self.status()
        except Exception as error:
            failure = failure or error
        finally:
            path = self.turn_path
            self.turn_path = None
            self.turn_task = None

        if path:
            try:
                self.apply_history(*(await self.history(path)))
            except Exception as error:
                refresh_failure = error
        if failure or refresh_failure:
            persisted_error = self.messages[-1].get("error_message") if self.messages else None
            if not isinstance(failure, ApiError) or not persisted_error or persisted_error != failure.message:
                self.fault = str(failure or refresh_failure)
            self.status(" · ".join(str(error) for error in (failure, refresh_failure) if error), True)

    async def cancel(self) -> None:
        if self.turn_task and not self.accepted:
            self.turn_task.cancel()
            self.status("Turn cancelled.")
        elif self.turn_path:
            try:
                await self.api.json(f"{self.turn_path}/cancel", method="POST")
            except Exception as error:
                if self.turn_path:
                    self.status(str(error), True)

"""Conversation state and turn orchestration for the Textual client."""

import asyncio
import time
from contextlib import aclosing
from pathlib import Path
from uuid import uuid4

from httpx import TimeoutException

from .cloud import BUILTIN_PROVIDER_ID
from .core import ApiError, CoreApi, entity
from .model_metadata import PiCatalog, effective_metadata, legacy_metadata, model_overrides
from .tools import execute_tool

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
        self.project: dict | None = None
        self.session = None
        self.messages: list[dict] = []
        self.has_older = False
        self.live: list[dict] = []
        self.fault = ""
        self.sync_fault = ""
        self.notice = ""
        self.notice_error = False
        self.update = lambda: None
        self.turn_task: asyncio.Task | None = None
        self.turn_path: str | None = None
        self.accepted = False
        self.catalog = PiCatalog()
        self.tool_mode: str | None = None
        self.tool_task: asyncio.Task | None = None

    async def choose_tools(self, mode: str) -> None:
        if mode not in ("off", "read", "write"):
            raise ValueError("Unknown tool mode.")
        if not self.project:
            raise ValueError("Open a trusted workspace with /project before enabling tools.")
        if self.session:
            path = entity("conversations", self.session["id"])
            current = await self.api.json(path)
            self.session = await self.api.json(path, method="PUT", body={
                **current, "override_tools_enabled": mode != "off",
                "writable": mode == "write", "updated_at": now_ms(),
            })
        self.tool_mode = mode
        self.status(f"Tools: {mode}.")

    def status(self, text: str = "", error: bool = False) -> None:
        self.notice = text
        self.notice_error = error
        self.update()

    async def refresh_model(self) -> None:
        self.model = await model_for(self.api, self.agent, self.session)
        self.update()

    @property
    def project_id(self) -> str | None:
        return self.project["id"] if self.project else None

    async def local_workspaces(self) -> list[tuple[Path, dict]]:
        workspaces = []
        for project in await self.api.list("/projects"):
            if project["owner"]["kind"] != "local":
                continue
            try:
                root = Path(project["workspace"]).expanduser()
                if not root.is_absolute():
                    continue
                root = root.resolve(strict=True)
            except (OSError, RuntimeError, ValueError):
                continue
            workspaces.append((root, project))
        return workspaces

    async def enclosing_project(self, directory: Path) -> dict | None:
        matches = [(len(root.parts), project) for root, project in await self.local_workspaces()
                   if directory.is_relative_to(root)]
        return max(matches, key=lambda match: match[0])[1] if matches else None

    async def use_workspace(self, directory: Path, *, trusted: bool = False) -> bool:
        project = await self.enclosing_project(directory)
        if project is None:
            if not trusted:
                return False
            if directory.resolve(strict=True) != directory or not directory.is_dir():
                raise ValueError("The workspace changed. Reopen Messenger to review the directory.")
            await self.save_project(directory.name or str(directory), directory)
            return True
        if not project.get("device_id"):
            project = await self.api.json("/projects", method="PUT", body=project)
        self.project = project
        return True

    async def save_project(self, name: str, directory: Path) -> None:
        workspaces = await self.local_workspaces()
        existing = next((project for root, project in workspaces if root == directory), None)
        entering = self.project_id is None
        if entering and existing:
            project = existing
            if not project.get("device_id"):
                project = await self.api.json("/projects", method="PUT", body=project)
            action = "Opened"
        else:
            now = now_ms()
            if entering:
                current = {"id": str(uuid4()), "created_at": now}
                action = "Created"
            else:
                current = await self.api.json(entity("projects", self.project_id))
                if current["owner"]["kind"] != "local":
                    raise ValueError("This project belongs to another device.")
                if existing and existing["id"] != current["id"]:
                    raise ValueError("This folder already belongs to another local project.")
                action = "Updated"
            project = await self.api.json("/projects", method="PUT", body={
                **current, "name": name, "workspace": str(directory), "updated_at": now,
            })
        if entering:
            await self.new()
        self.project = project
        self.status(f"{action} project {project['name']}.")

    async def leave_project(self) -> None:
        await self.new()
        self.project = None
        self.status("Switched to standalone chat.")

    async def list_sessions(self) -> list[dict]:
        if self.project_id is not None:
            return await self.api.list(f"{entity('projects', self.project_id)}/conversations")
        return [row for row in await self.api.list("/conversations") if row.get("project_id") is None]

    async def new(self, agent: dict | None = None) -> None:
        owner = agent or self.agent
        self.model = await model_for(self.api, owner)
        self.agent = owner
        self.session = None
        self.messages = []
        self.has_older = False
        self.live = []
        self.fault = ""
        self.tool_mode = None

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
        if conversation.get("project_id") != self.project_id:
            raise ValueError("This conversation belongs to a different workspace.")
        owner = await self.api.json(entity("agents", conversation["agent_id"]))
        self.model = await model_for(self.api, owner, conversation)
        self.agent = owner
        self.apply_history(conversation, history)
        enabled = conversation.get("override_tools_enabled")
        self.tool_mode = None if enabled is None else "off" if not enabled else (
            "write" if conversation.get("writable") else "read"
        )
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

    async def save_provider(self, values: dict) -> dict:
        now = now_ms()
        created_at = values.setdefault("created_at", now)
        provider_id = values.setdefault("id", str(uuid4()))
        return await self.api.json(entity("providers", provider_id), method="PUT", body={
            "id": provider_id, "name": values["name"], "base_url": values["base_url"],
            "api_format": values["api_format"], "api_key": values["api_key"],
            "created_at": created_at, "updated_at": now,
        })

    async def delete_provider(self, provider_id: str) -> None:
        await self.api.json(entity("providers", provider_id), method="DELETE")
        if self.model and self.model["provider_id"] == provider_id:
            self.model = None
            self.update()

    async def provider_models(self, provider_id: str) -> list[dict]:
        return await self.api.list(f"{entity('providers', provider_id)}/models")

    async def save_model(self, values: dict) -> dict:
        identifier = values.setdefault("id", str(uuid4()))
        values.setdefault("created_at", now_ms())
        if values.get("metadata") is not None:
            metadata = effective_metadata(values["model_id"], values["metadata"], values.get("metadata_overrides") or {})
            values.update(
                display_name=metadata["displayName"],
                context_window=metadata.get("contextWindow", 0),
                input_modalities=",".join(metadata["inputModalities"]),
                supports_thinking=metadata.get("reasoning", False),
                supports_temperature=metadata["supportsTemperature"],
            )
        model = await self.api.json(entity("models", identifier), method="PUT", body=values)
        if self.model and self.model["id"] == identifier:
            self.model = model
            self.update()
        return model

    async def set_model_enabled(self, model: dict, enabled: bool) -> dict:
        path = entity("models", model["id"])
        await self.api.json(f"{path}/enabled", method="PUT", body={"enabled": enabled})
        saved = {**model, "is_enabled": enabled}
        if self.model and self.model["id"] == saved["id"]:
            self.model = saved
            self.update()
        return saved

    async def delete_model(self, identifier: str) -> None:
        await self.api.json(entity("models", identifier), method="DELETE")
        if self.model and self.model["id"] == identifier:
            self.model = None
            self.update()

    async def discover_models(self, provider: dict) -> list[dict]:
        key = provider["api_key"]
        try:
            return await self.api.json("/models/discover", method="POST", body={
                "baseUrl": provider["base_url"], "apiKey": key, "apiFormat": provider["api_format"],
                "skipCatalog": True,
            }, timeout=45)
        except TimeoutException:
            raise ApiError("discovery_timeout", "Model discovery timed out.") from None
        except Exception as error:
            message = str(error)
            raise ApiError("discovery_failed", message.replace(key, "••••") if key else message) from None
    async def fetch_models(self, provider: dict) -> str:
        discovered = await self.discover_models(provider)
        if not discovered:
            return "No models returned by the provider."
        catalog, warning = await self.catalog.load()
        existing = {model["model_id"]: model for model in await self.provider_models(provider["id"])}
        imported = refreshed = 0
        seen = set()
        for model in discovered:
            identifier = model["model_id"]
            if not identifier.strip() or identifier in seen:
                continue
            seen.add(identifier)
            if previous := existing.get(identifier):
                overrides = model_overrides(previous)
                metadata = {
                    **(previous.get("metadata") if previous.get("metadata") is not None else legacy_metadata(previous)),
                    **catalog.match(identifier), **model["metadata"],
                }
                if metadata != previous.get("metadata"):
                    await self.save_model({**previous, "metadata": metadata, "metadata_overrides": overrides})
                    refreshed += 1
            else:
                await self.save_model({
                    **model, "provider_id": provider["id"], "is_enabled": True,
                    "metadata": {**catalog.match(identifier), **model["metadata"]},
                })
                imported += 1
        return f"Imported {imported} new models. Updated metadata for {refreshed}. {warning}".strip()

    async def fetch_model_metadata(self, provider: dict, model_id: str) -> tuple[dict, str]:
        discovered, catalog_result = await asyncio.gather(
            self.discover_models(provider), self.catalog.load(), return_exceptions=True,
        )
        if isinstance(catalog_result, Exception):
            raise catalog_result
        catalog, warning = catalog_result
        metadata = catalog.match(model_id)
        if isinstance(discovered, Exception):
            warning = f"Provider discovery failed. {discovered} {warning}".strip()
        else:
            upstream = next((model["metadata"] for model in discovered if model["model_id"] == model_id), {})
            metadata = {**metadata, **upstream}
        return metadata, warning

    async def connect(self, values: dict) -> None:
        provider = await self.save_provider(values)
        model_id = values.setdefault("record_id", str(uuid4()))
        model = await self.api.json("/models", method="PUT", body={
            "id": model_id, "provider_id": provider["id"], "model_id": values["model_id"],
            "display_name": values["model_id"], "is_enabled": True, "created_at": provider["created_at"],
        })
        await self.choose_model(model)
        try:
            result = await self.fetch_models(provider)
        except Exception as error:
            self.status(f"Model set to {model['display_name']}. Fetching more models failed. {error}", True)
        else:
            self.status(result)

    async def create_session(self, content: str) -> dict:
        now = now_ms()
        settings = {} if self.tool_mode is None else {
            "override_tools_enabled": self.tool_mode != "off", "writable": self.tool_mode == "write",
        }
        return await self.api.json("/conversations", method="PUT", body={
            "id": str(uuid4()), "title": content.splitlines()[0][:64], "provider_id": self.model["provider_id"],
            "agent_id": self.agent["id"], "project_id": self.project_id, "created_at": now, "updated_at": now,
            **settings,
        })

    async def upload_conversations(self, conversation_id: str | None = None, *, claim_new: bool = False) -> None:
        try:
            result = await self.api.json("/cloud/conversations/push", method="POST", body={
                "conversationId": conversation_id, "claimNew": claim_new,
            }, timeout=25 if conversation_id else 125)
        except Exception as error:
            detail = f"Cloud upload failed. {error}"
            if isinstance(error, ApiError) and error.code == "session_expired":
                detail += " Sign in again with /cloud, then use /sync."
                if self.model and self.model["provider_id"] == BUILTIN_PROVIDER_ID:
                    self.model = None
            self.sync_fault = detail
            self.status(detail, True)
            return
        self.sync_fault = ""
        if result["conflicts"]:
            self.sync_fault = (
                f"Cloud has newer changes for {result['conflicts']} conversations. "
                f"Uploaded {result['uploaded']} other conversations."
            )
            self.status(self.sync_fault, True)
            return
        if conversation_id is None:
            skipped = f" Skipped {result['skipped']} conversations." if result["skipped"] else ""
            self.status(f"Uploaded {result['uploaded']} conversations to Messenger Cloud.{skipped}")
        elif result["uploaded"] and not self.notice:
            self.status("Conversation synced to Messenger Cloud.")
        elif result["skipped"] and not self.notice:
            self.status("Conversation upload skipped. Use /sync to upload eligible local conversations.")
        else:
            self.update()

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
        new_session = self.session is None
        terminal = False
        try:
            if not self.model:
                raise ApiError("model_required", "Choose a model with /models or add one through /providers.")
            if not self.model["is_enabled"]:
                raise ApiError("model_disabled", "Choose an enabled model with /models before sending.")
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
                            self.tool_task = asyncio.create_task(execute_tool(
                                self.project, bool(self.session.get("writable")), event["name"], event["argumentsJson"],
                            ))
                            try:
                                result = await self.tool_task
                            except asyncio.CancelledError:
                                if asyncio.current_task().cancelling():
                                    raise
                                # Core's cancel endpoint already released the
                                # pending call. A late result would return 404.
                                continue
                            finally:
                                self.tool_task = None
                            await self.api.json(f"{path}/result", method="POST", body=result)
                        case "ToolCallFinished":
                            failed = event["isError"]
                            self.status(f"Tool failed · {event['name']}" if failed else "Waiting for the model…", failed)
                        case "Error":
                            terminal = True
                            failure = ApiError(event["code"], event["message"])
                        case "Cancelled":
                            terminal = True
                            self.status("Turn cancelled.")
                        case "Finished":
                            terminal = True
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
        if terminal and self.session and self.session.get("project_id") is None:
            await self.upload_conversations(self.session["id"], claim_new=new_session)

    async def cancel(self) -> None:
        if self.turn_task and not self.accepted:
            self.turn_task.cancel()
            self.status("Turn cancelled.")
        elif self.turn_path:
            try:
                await self.api.json(f"{self.turn_path}/cancel", method="POST")
                if self.tool_task:
                    self.tool_task.cancel()
            except Exception as error:
                if self.turn_path:
                    self.status(str(error), True)

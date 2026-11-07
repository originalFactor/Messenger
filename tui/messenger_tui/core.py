"""The stdio lifecycle bridge and authenticated local REST/SSE client."""

import asyncio
import json
from contextlib import asynccontextmanager, suppress
from pathlib import Path
from urllib.parse import quote, urlsplit

import httpx


def entity(collection: str, identifier: str) -> str:
    return f"/{collection}/{quote(identifier, safe='')}"


class ApiError(Exception):
    def __init__(self, code: str, message: str, detail: str | None = None):
        self.code = code
        self.message = message
        super().__init__(f"{code}: {message}" + (f" ({detail})" if detail and detail != message else ""))


class CoreApi:
    def __init__(self, client: httpx.AsyncClient):
        self.client = client

    @staticmethod
    def check(response: httpx.Response) -> None:
        if response.is_success:
            return
        try:
            error = response.json()
        except ValueError:
            error = {"message": response.text}
        if not isinstance(error, dict):
            error = {}
        raise ApiError(
            error.get("code") or f"http_{response.status_code}",
            error.get("message") or response.reason_phrase,
            error.get("detail"),
        )

    async def json(self, path: str, *, method: str = "GET", body=None, params=None, timeout=httpx.USE_CLIENT_DEFAULT):
        response = await self.client.request(method, path, json=body, params=params, timeout=timeout)
        self.check(response)
        if response.status_code not in (202, 204):
            return response.json()

    async def list(self, path: str) -> list[dict]:
        items = []
        offset = 0
        while offset is not None:
            page = await self.json(path, params={"limit": 100, "offset": offset})
            items.extend(page["items"])
            offset = page.get("nextOffset")
        return items

    async def turn(self, path: str, body: dict):
        finished = False
        try:
            async with self.client.stream(
                "POST", f"{path}/messages", json=body, timeout=httpx.Timeout(15, read=None)
            ) as response:
                if not response.is_success:
                    await response.aread()
                    self.check(response)
                if not response.headers.get("content-type", "").startswith("text/event-stream"):
                    raise ApiError("invalid_stream", "Core did not return a chat event stream.")
                data = []
                async for line in response.aiter_lines():
                    if line.startswith("data:"):
                        data.append(line[5:].removeprefix(" "))
                    elif not line and data:
                        try:
                            event = json.loads("\n".join(data))
                        except ValueError as error:
                            raise ApiError("invalid_stream", "Core returned an invalid chat event.") from error
                        data.clear()
                        if not isinstance(event, dict) or not isinstance(event.get("type"), str):
                            raise ApiError("invalid_stream", "Core returned a chat event without a type.")
                        finished |= event["type"] in ("Finished", "Cancelled", "Error")
                        yield event
        except httpx.HTTPError as error:
            if finished:
                raise
            raise ApiError("stream_ended", "Chat stream ended before the turn finished.", str(error)) from error
        if not finished:
            raise ApiError("stream_ended", "Chat stream ended before the turn finished.")


class CoreProcess:
    def __init__(self, process: asyncio.subprocess.Process):
        self.process = process
        self.stderr = bytearray()
        self.drain = asyncio.create_task(self.read_stderr())

    async def read_stderr(self) -> None:
        while chunk := await self.process.stderr.read(4096):
            self.stderr.extend(chunk)
            del self.stderr[:-4096]

    async def wait(self) -> str:
        code = await self.process.wait()
        await asyncio.shield(self.drain)
        return self.stderr.decode(errors="replace").strip() or f"Core exited ({code})."

    async def stop(self) -> None:
        self.process.stdin.close()
        try:
            await asyncio.wait_for(self.wait(), 6)
        except TimeoutError:
            if self.process.returncode is None:
                with suppress(ProcessLookupError):
                    self.process.kill()
            await self.process.wait()
            await asyncio.gather(self.drain, return_exceptions=True)


@asynccontextmanager
async def open_core(executable: Path, database: Path):
    try:
        process = await asyncio.create_subprocess_exec(
            str(executable), str(database), stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as error:
        raise RuntimeError("Core executable missing. Build it first or pass --core PATH.") from error
    core = CoreProcess(process)
    try:
        line = await asyncio.wait_for(process.stdout.readline(), 30)
        if not line:
            raise RuntimeError(await core.wait())
        try:
            handshake = json.loads(line)
            address = urlsplit(handshake["url"])
            token = handshake["token"]
            valid = (
                address.scheme == "http" and address.hostname == "127.0.0.1" and address.port
                and not address.username and not address.password
                and not address.path and not address.query and not address.fragment
                and isinstance(token, str) and token and token.isascii() and token.isprintable()
            )
        except (KeyError, TypeError, ValueError):
            valid = False
        if not valid:
            raise RuntimeError("Invalid loopback Core handshake.")
        async with httpx.AsyncClient(
            base_url=f"http://127.0.0.1:{address.port}/api/v1/",
            headers={"Authorization": f"Bearer {token}"}, timeout=15,
            follow_redirects=False, trust_env=False,
        ) as client:
            yield CoreApi(client), core
    finally:
        await core.stop()

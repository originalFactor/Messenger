"""Messenger Cloud account actions through the local Core API."""

import asyncio
from dataclasses import dataclass
from urllib.parse import urlsplit

from .core import CoreApi

BUILTIN_PROVIDER_ID = "builtin-messenger-cloud-ai"


async def sync_builtin_models(api: CoreApi, *, force: bool = False) -> int:
    return await api.json("/cloud/builtin-models/sync", method="POST", body={"force": force}, timeout=45)


def normalize_server_url(value: str) -> str:
    url = value.strip().rstrip("/")
    try:
        parsed = urlsplit(url)
        valid = (
            parsed.scheme in ("http", "https")
            and parsed.hostname
            and parsed.username is None
            and parsed.password is None
            and not any(char in url for char in "\\?#")
            and not any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in url)
            and parsed.port != 0
        )
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("Enter an HTTP(S) Cloud base URL without credentials, a query, or a fragment.")
    return url


@dataclass
class CloudAccount:
    api: CoreApi
    server_url: str
    user: dict | None

    @property
    def has_ai_key(self) -> bool:
        return bool(self.user and (self.user.get("aiApiKey") or "").strip())

    @classmethod
    async def load(cls, api: CoreApi) -> "CloudAccount":
        url, user = await asyncio.gather(api.json("/cloud/server-url"), api.json("/cloud/me"))
        return cls(api, url, user)

    async def set_server(self, value: str) -> bool:
        url = normalize_server_url(value)
        if url == self.server_url.rstrip("/"):
            return False
        # Clear the previous account on its own server before changing where
        # Core sends authenticated requests.
        signed_out = self.user is not None
        if signed_out:
            await self.logout()
        await self.api.json("/cloud/server-url", method="PUT", body={"url": url})
        self.server_url = url
        return signed_out

    async def login(self, value: str, email: str, password: str) -> None:
        email = email.strip()
        if not email or not password:
            raise ValueError("Enter your Messenger Cloud email and password.")
        await self.set_server(value)
        self.user = await self.api.json("/cloud/login", method="POST", body={
            "email": email, "password": password,
        })

    async def logout(self) -> None:
        await self.api.json("/cloud/logout", method="POST")
        self.user = None

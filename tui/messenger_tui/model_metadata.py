"""Pi catalog lookup and capability defaults shared by discovery and editing."""

import re
import time

import httpx

PI_CATALOG_URL = "https://pi.dev/api/models"
CANONICAL_PROVIDERS = {
    "deepseek", "openai", "anthropic", "google", "google-vertex", "zai", "moonshotai",
    "qwen", "minimax", "xai", "mistral", "meta", "amazon-bedrock",
}
PREFIX = re.compile(r"^(?:[^:/]+/|[^:/]+:(?!(?:\d+|free|extended|nitro|online)$))(?=.)", re.I)
EFFORT_SUFFIX = re.compile(r"-(thinking|high|low|medium)$", re.I)
BEDROCK = re.compile(
    r"^(?:(?:us|eu|apac|global)\.)?(?:amazon|anthropic|cohere|deepseek|meta|mistral|nvidia|openai|qwen|writer|ai21)\.(.+)$",
    re.I,
)


def candidates(identifier: str) -> list[str]:
    bare = PREFIX.sub("", identifier)
    names = dict.fromkeys((identifier, EFFORT_SUFFIX.sub("", identifier), bare, EFFORT_SUFFIX.sub("", bare)))
    for name in list(names):
        unwrapped = PREFIX.sub("", name)
        names[unwrapped] = None
        if match := BEDROCK.match(unwrapped):
            vendor_id = match[1]
            names[vendor_id] = None
            names[re.sub(r"-v\d+(?::\d+)?$", "", vendor_id, flags=re.I)] = None
    return list(names)


class ModelCatalog:
    def __init__(self, models: dict):
        self.models = models
        self.folded = {}
        self.aliases = {}
        for index, (key, model) in enumerate(models.items()):
            self.folded.setdefault(key.lower(), model)
            for name in candidates(key):
                self.aliases.setdefault(name.lower(), (index, model))

    def match(self, identifier: str) -> dict:
        names = candidates(identifier)
        for name in names:
            if name in self.models:
                return self.models[name]
            if name.lower() in self.folded:
                return self.folded[name.lower()]
        matches = [self.aliases[name.lower()] for name in names if name.lower() in self.aliases]
        return min(matches, key=lambda match: match[0])[1] if matches else {}


def decode_catalog(payload: dict) -> dict:
    models = {}
    aliases = {}
    for provider, entries in payload.items():
        if not isinstance(entries, dict):
            continue
        for identifier, entry in entries.items():
            if not isinstance(entry, dict):
                continue
            metadata = {
                key: entry[key] for key in ("reasoning", "supportsTemperature", "supportsTopP", "supportsPenalties")
                if type(entry.get(key)) is bool
            }
            if isinstance(name := entry.get("name"), str) and name.strip():
                metadata["displayName"] = " ".join(word[0].upper() + word[1:]
                                                   for word in name.rsplit("/", 1)[-1].replace("-", " ").split())
            if isinstance(modalities := entry.get("input"), list) and all(isinstance(item, str) for item in modalities):
                metadata["inputModalities"] = modalities
            for key, source in (("contextWindow", "contextWindow"), ("maxOutputTokens", "maxTokens")):
                if type(value := entry.get(source)) is int and value > 0:
                    metadata[key] = value
            compat = entry.get("compat")
            if isinstance(compat, dict) and type(compat.get("supportsTemperature")) is bool:
                metadata.setdefault("supportsTemperature", compat["supportsTemperature"])
            levels = entry.get("thinkingLevelMap")
            levels = levels if isinstance(levels, dict) else {}
            efforts = []
            if metadata.get("reasoning"):
                if isinstance(off := levels.get("off"), str) and off:
                    efforts.append(off)
                for level in ("minimal", "low", "medium", "high", "xhigh", "max"):
                    value = levels.get(level, level if level not in ("xhigh", "max") else None)
                    if isinstance(value, str) and value:
                        efforts.append(value)
            metadata["reasoningEfforts"] = list(dict.fromkeys(efforts))
            models[f"{provider}/{identifier}"] = metadata
            existing = aliases.get(identifier)
            if not existing or (existing[0] not in CANONICAL_PROVIDERS and provider in CANONICAL_PROVIDERS):
                aliases[identifier] = (provider, metadata)
    for identifier, (_, metadata) in aliases.items():
        models.setdefault(identifier, metadata)
    return models


class PiCatalog:
    def __init__(self):
        self.catalog = ModelCatalog({})
        self.expires = 0.0
        self.warning = ""

    async def load(self) -> tuple[ModelCatalog, str]:
        if time.monotonic() >= self.expires:
            try:
                async with httpx.AsyncClient(timeout=8) as client:
                    response = await client.get(PI_CATALOG_URL)
                    response.raise_for_status()
                    payload = response.json()
                models = decode_catalog(payload) if isinstance(payload, dict) else {}
                if not models:
                    raise ValueError("empty model catalog")
            except (httpx.HTTPError, ValueError):
                self.warning = "Pi catalog unavailable. Saved and provider metadata are still available."
                self.expires = time.monotonic() + 60
            else:
                self.catalog = ModelCatalog(models)
                self.warning = ""
                self.expires = time.monotonic() + 24 * 60 * 60
        return self.catalog, self.warning


def legacy_metadata(model: dict) -> dict:
    metadata = {}
    if model.get("context_window"):
        metadata["contextWindow"] = model["context_window"]
    if model.get("input_modalities"):
        metadata["inputModalities"] = model["input_modalities"].split(",")
    if model.get("supports_thinking"):
        metadata["reasoning"] = True
    if model.get("supports_temperature"):
        metadata["supportsTemperature"] = True
    return metadata


def model_overrides(model: dict) -> dict:
    if model.get("metadata_overrides") is not None:
        return dict(model["metadata_overrides"])
    name = model.get("display_name")
    return {"displayName": name} if model.get("metadata") is None and name and name != model["model_id"] else {}


def effective_metadata(model_id: str, metadata: dict, overrides: dict) -> dict:
    result = {
        "displayName": model_id, "inputModalities": ["text"],
        "supportsTemperature": True, "supportsTopP": True, "supportsPenalties": True,
        **metadata, **overrides,
    }
    if result.get("reasoning") is False:
        result["reasoningEfforts"] = []
    if result.get("defaultReasoningEffort") not in result.get("reasoningEfforts", []):
        result.pop("defaultReasoningEffort", None)
    return result

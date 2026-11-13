# Device-local assets and BYOK image generation

All routes below are under `/api/v1` and require the local per-start bearer token. Never embed that token in an artifact URL, export manifest, or generated webpage. Image generation is independent of chat protocols and model modality metadata. It uses exactly the configured provider's `/images/generations` endpoint, with no retry, alternate endpoint, or provider fallback.

## Assets

Bytes live under the independent Core data directory's `assets/` in SHA-256 named files. The `local_asset:<hash>` Store KV namespace contains typed metadata only. Assets survive chat deletion and Cloud replacement. Files are staged, synced, and atomically renamed before metadata is committed. Interrupted staging is removed on service initialization. Explicit references establish ownership. No chat cleanup removes these files. Explicit GC removes only assets whose last reference was released at least 24 hours earlier, plus hash-named orphan files older than 24 hours. Full local-data clearing removes owned files and metadata.

- `GET /assets`: metadata manifest (`Asset[]`).
- `POST /assets/import`: `{dataBase64,mimeType,owner:{ownerType,ownerId}}` → `Asset`.
- `POST /assets/download`: `{url,owner:{ownerType,ownerId}}` → `Asset`.
- `GET /assets/{id}`: asset metadata.
- `GET /assets/{id}/provenance`: sanitized generated-image provenance entries, or an empty array for imported generic resources.
- `GET /assets/{id}/content`: binary bytes with recorded MIME, `Content-Disposition: attachment`, `nosniff`, and private/no-store caching. Kotlin consumers fetch using bearer auth. An artifact host must fetch via its trusted host and grant only explicitly owned resources; generated code must not receive the bearer.
- `POST /assets/{id}/references`: `{ownerType,ownerId}` → updated asset.
- `POST /assets/{id}/unreference`: `{ownerType,ownerId}` → updated asset. Refuses to release a still-bound artifact, material or active/result-owning image job.
- `POST /assets/gc`: no body → `{removed:<count>}`. Removes only fully unreferenced assets after a 24-hour grace period and old orphan files; referenced assets are retained.

`Asset` is `{id,sha256,mimeType,size,width,height,createdAt,references}`. Dimensions are nullable for generic resources. IDs equal lowercase SHA-256, never a local path. The manifest includes explicit owned references and no credentials or absolute paths. Artifact services call `AssetService::import_bytes(bytes,mime,owner)` and `read_bytes(id)` to handle text and binary resources without base64 in KV. Downloads require public HTTPS port 443, disable environment proxies, validate all DNS answers and pin validated addresses, and revalidate at most three redirects. The complete download is bounded by 60 seconds and 20 MiB. Limits are 20 MiB per file, 512 MiB catalog content, 10,000 assets and 10,000 references per asset. PNG/JPEG/WebP declarations require matching decoded content, at most 8192 pixels per dimension and 32 million pixels with bounded decoder allocation. Generic files are stored as data; importing JavaScript/HTML does not execute or authorize it.

## Image profiles and capability

- `GET /image-generation/capability` → `{enabled:false}` initially.
- `PUT /image-generation/capability` with `{enabled:true|false}` explicitly controls paid image generation.
- `GET /image-generation/profiles` → profiles.
- `PUT /image-generation/profiles/{id}` saves the complete profile; path and payload IDs must match.
- `DELETE /image-generation/profiles/{id}` removes configuration while retaining jobs and results.

Profile JSON:

```json
{
  "id": "illustrations",
  "name": "Illustrations",
  "providerId": "existing-provider-id",
  "modelId": "remote-generation-model-id",
  "adapter": "openai_images",
  "enabled": false,
  "supportedParameters": ["n", "size", "quality", "response_format"],
  "defaults": {"n": 1, "response_format": "b64_json"}
}
```

`modelId` is an explicit remote image model identifier, independent of the chat model catalog or provider conversation format. No executable capability is inferred from `outputModalities`. Profiles default disabled. The built-in Cloud provider is rejected until it explicitly supplies an image-generation capability. Credentials remain in the existing provider row. Missing provider, key, profile, or model fails admission before a paid task is created. A profile's declared supported parameters are constrained to `n`, `size`, `quality`, `style`, `response_format`, `background`, `output_format`, `output_compression`, and `moderation`. Unsupported parameters fail before sending. Provider-specific unsupported values surface as provider failures.

## Durable jobs

- `POST /image-generation/jobs` with `{requestId,profileId,prompt,parameters:{}}` returns a queued job and begins one background request.
- `GET /image-generation/jobs` lists durable jobs.
- `GET /image-generation/jobs/{id}` gets authoritative status; clients poll while pending.
- `POST /image-generation/jobs/{id}/cancel` cancels locally and returns current status.

Jobs contain `{id,request,profile,parameters,status,createdAt,updatedAt,assetIds,error,usage,revisedPrompts}`. Times are UTC epoch milliseconds. `profile` and merged `parameters` snapshot the actual submitted configuration without credentials. The request ID deterministically identifies the job. An identical request returns its existing job even if the profile has since changed or been deleted. Reusing the ID with different request content returns 409 `request_id_conflict`. A user-requested retry must use a new ID and is a new potentially paid request.

Statuses are `queued`, `running`, `succeeded`, `failed`, `cancelled`, `unknown`. Local cancellation aborts the HTTP operation, but the provider may already have generated or charged. Transport failures, ambiguous malformed responses, and interrupted pending jobs recovered on startup become `unknown`; they are never automatically resubmitted. HTTP errors retain status/details in the durable failure. Base64 and URL outputs are validated and promptly copied into owned assets, not left as expiring remote URLs. The response supports one through four images, a bounded response body, and optional opaque usage metadata; no token price is reported as image cost.

Storage namespaces are `local_image_profile:`, `local_image_job:`, and `image_generation_enabled`. These device-local records do not join Cloud sync. Resource references use `{ownerType:"imageGenerationJob",ownerId:<jobId>}`. Failed/cancelled multi-image requests can retain already imported assets under this job owner; they are not automatically deleted or re-requested. Full local cleanup calls `images.clear().await` before `assets.clear().await` to cancel active work and clear configuration/results.

## Core-owned tool

`messenger_generate_image` accepts `{action:"listProfiles"}` to discover enabled profiles before generation. This checks the effective turn permission and global opt-in, returns `{profiles:[{id,name,modelId}]}` (an empty list succeeds), and creates no job or provider request. No credentials or provider connection details are included. The tool description instructs the model to discover profiles first when their IDs are unknown.

Generation accepts `{action:"generate",requestId,profileId,prompt,parameters}` or the legacy `{requestId,profileId,prompt,parameters}` without an action. A profile ID remains required; discovery never selects an implicit provider fallback. The tool schema uses `anyOf` so discovery requires no generation fields, while either generation form requires request ID, profile ID and prompt. The REST job endpoint retains its existing strict request shape without an `action` field. It requires the global explicit capability plus effective Agent/turn permission, works without a workspace or Runtime, and waits for the durable terminal result. Cancellation is linked to the owning turn and server shutdown. It returns readable job JSON plus structured content:

```json
{"version":1,"type":"imageGenerationJob","jobId":"...","assetIds":["..."],"status":"succeeded"}
```

A non-success terminal status is a tool error. Clients must render/surface errors through their normal visible error paths. The tool does not change chat model admission rules, start additional turns, or retry paid work. Reserved tool-name enforcement and ACP owning-turn policy are integrated by the HTTP routing layer.

Jobs/profiles/assets currently use authenticated GET polling and existing KV store changes, not new domain-specific SSE events. Browser embedding, image editing, direct image outputs from chat protocols, scheduled automatic GC, and real paid-provider verification are outside this service implementation.

## Full portable learning packages

`GET /learning/package/export` returns a versioned JSON document:

```json
{
  "formatVersion": 1,
  "learning": {"formatVersion":1,"records":[],"revisions":[],"reviewStates":[],"attempts":[],"externalResourcesIncluded":false},
  "assets": [{"metadata":{"id":"sha256","sha256":"sha256","mimeType":"application/octet-stream","size":1,"width":null,"height":null,"createdAt":0,"references":[],"unreferencedAt":null},"dataBase64":"AA=="}],
  "artifacts": []
}
```

The inner `learning` envelope is the existing typed learning metadata format; the complete outer package adds every owned asset's bytes and every artifact's full immutable revisions, resource bindings, and persisted application state. Artifact `approvedDomains` is always emptied during export; imported nonempty grants are rejected. `trustedOnDevice` is cleared during export and forcibly reset to false during import, even if the input claims true. Provider rows, API keys, bearer credentials, runtime capability flags, and network permissions are not exported. Imported artifacts still require local execution/network permission.

`POST /learning/package/preview` accepts that exact package and returns `{formatVersion:1,records,revisions,attempts,assetCount,assetBytes,artifactCount,externalResourcesIncluded:true,warnings}`. Preview checks IDs, complete/ordered immutable revisions, current state format, resource references, safe paths, MIME, decoded image dimensions, content hashes, base64, per-file and total quotas, and the actual learning-store transactional validation without committing. Limits are 96 MiB total JSON, 64 MiB decoded assets, 10,000 assets and 1,000 artifacts in addition to the learning and per-artifact limits. Oversized exports fail explicitly rather than omitting resources.

`POST /learning/package/import` accepts the same body and returns `{imported:true,preview:<preview>}`. Learning/artifact IDs are preserved, and collisions are rejected without overwrite or implicit remapping. Existing identical assets retain all original local references; imported portable artifact/learning references and a package owner are merged. Source-device runtime ownership is not authority on the destination. Asset bindings in all learning revisions and artifact revisions must resolve inside the package, making the export self-contained.

Import holds the domain mutation and asset locks, performs full validation, stages/syncs/atomically renames all new files, then commits learning records, revision/review history, asset metadata and artifact records in one SQLite transaction. A validation/staging/transaction failure publishes no partial learning or resource metadata and removes newly staged files. A process crash before the transaction can leave only unowned hash files; explicit GC reclaims them after the grace period. Existing files/references are never removed as rollback compensation. The original metadata-only `/learning/export` and `/learning/import*` endpoints remain separate compatibility routes.

Optional `generationProvenance` maps asset IDs to sanitized provenance records: `{prompt,modelId,parameters,revisedPrompt,createdAt,completedAt,usage}`. `revisedPrompt` and `usage` may be null. The provenance namespace is `provenance:<assetId>` and import merges it in the same transaction, preserving preexisting local entries. Every map key must identify an included asset, with at most 128 records per asset. The generation adapter records these after importing each decoded output. They include prompt, remote model identifier, actual submitted parameters, revised prompt and timestamps without provider IDs, base URLs, keys, job execution state or profile settings. Usage is restricted to bounded nested numeric counters (no strings, arrays or opaque provider-returned metadata), so portable provenance cannot carry credential-bearing provider fields. Unknown record fields are rejected. Prompt/revised-prompt limits are 32,000 bytes, model identifiers 256 bytes, usage 8 KiB, and parameters use the same supported Images API value validation. Jobs/profiles remain device-local; portable result provenance does not enable paid generation.

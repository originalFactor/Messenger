# Remote projects

A project is owned by one device (`projects.deviceId`, API field `device_id`).
Its workspace exists only there. A chat turn may run on any device of the
account, but the project's workspace-bound tools (`terminal`, `glob`, `grep`,
`read`, `edit`, `create`) always execute on the owner.

## Rules

- **Owner**: a project written through the local API without `device_id` keeps
  its recorded owner, else becomes owned by this device. `NULL` (legacy rows) is
  treated as local. The device id is generated once per installation and stored
  in `kv` (`device_id`); signing out does not change it.
- **Availability**: a project whose owner is another device is *available* only
  while that device is connected to the relay and this device is too. Otherwise
  `POST /conversations/{id}/messages` (send/retry/regenerate/continue) fails with
  409 `project_unavailable` before any row changes. History stays readable.
- **Execution**: only the tool call crosses devices; the LLM request, message
  persistence and SSE stream stay on the initiating device. Client-provided
  tools that are not workspace tools still run on the initiator.
- **Owner-side checks** (`messenger-remote`): the call must be addressed to this
  device, name a project this device owns, and a built-in workspace tool. `edit`
  and `create` additionally need the conversation to exist on the owner and be
  `writable` (so the conversation must have synced there first).
- **Offline mid-call**: presence dropping for the owner, losing the relay
  connection, a 10-minute call timeout or the turn being cancelled all end the
  waiting call with an `is_error` tool result; cancel/timeout also send
  `tool-cancel` so the owner stops the tool.
- Sync carries `deviceId` on project documents. A pull whose document has no
  `deviceId` keeps the owner already stored locally.

## Cloud relay contract (`/api/remote/*`)

All calls use the existing cloud session cookie. A `401` is terminal and drops
the local session like any other authenticated call. The relay only forwards;
it never interprets payloads, and scopes everything to the session's account.

`GET /api/remote/events?deviceId=<id>` — SSE, one connection per device.
Registers the device as online while connected (disconnect = offline). Events:

| event | data |
|---|---|
| `presence` | `{ "devices": [deviceId, …] }` — all connected devices of the account; sent on connect and on every change |
| `toolCall` | `{ callId, fromDeviceId, targetDeviceId, projectId, conversationId, name, argumentsJson }` |
| `toolResult` | `{ callId, targetDeviceId, output, isError }` |
| `toolCancel` | `{ callId, targetDeviceId }` |

`POST /api/remote/tool-call` (body = the `toolCall` data) → `202`; `404`/`409`/`410`
when `targetDeviceId` is not connected (the core reports the owner offline).
`POST /api/remote/tool-result` (`toolResult` data) and
`POST /api/remote/tool-cancel` (`toolCancel` data) → `202`. Each forwards to the
connection of `targetDeviceId` as the matching SSE event.

The core reconnects with 1 s→30 s backoff and polls every 3 s while signed out.

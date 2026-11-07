# Agent Client Protocol and device sessions

Messenger implements the stable ACP v1 protocol in Rust. The device Core owns
the turn, workspace, tools and durable session. The native client supplies its
local platform tool host. The web client streams through the account relay
described in [REMOTE.md](REMOTE.md).

## Connecting a local editor to Messenger

1. Start Messenger Desktop and configure a model for its default Agent.
2. Build the stdio adapter with
   `cargo build -p messenger-http --bin messenger-core-server` from `core/rust`.
3. Configure the editor to launch that binary with arguments
   `acp --connect <absolute path to acp-connection.json>`. Desktop writes this
   private descriptor under `~/.messenger/files/messenger_core/` at startup.
   On Windows use the user's home directory and the `.exe` binary.

The descriptor contains the loopback address and per-start bearer. Keep it in
the Desktop user profile. Restart the editor adapter after restarting
Messenger. The adapter speaks newline-delimited JSON-RPC on stdio and forwards
it to the authenticated loopback WebSocket. It uses the running Desktop Core
and the same SQLite store as native chat.

The Agent implements `initialize`, `session/new`, `session/load`,
`session/prompt` and `session/cancel`. It uses the configured default Agent
for new sessions and attaches a local project when the requested absolute
directory matches that project's workspace. It replays saved messages and tool events on load.
Prompts accept text and resource links as text context. Caller-supplied MCP
servers are unsupported. Tool policy and execution use the device's Messenger
settings.

## Running another ACP Agent

Install and sign in to the ACP Agent on the device first. On Android install
Messenger Runtime and perform the Agent setup in its terminal. Open **ACP and
device control** in Messenger settings and add an Agent profile, for example:

```json
[
  {
    "id": "my-agent",
    "name": "My Agent",
    "command": "/absolute/path/to/agent",
    "args": ["--acp"],
    "cwd": "/absolute/path/to/workspace"
  }
]
```

The optional `env` object maps environment variable names to strings. A
selected Messenger project overrides the profile's working directory. On
Android the command can be the absolute executable path or a name from
Runtime's bootstrap bin directory, and the working directory must be accessible
to Runtime. ACP keeps the Runtime terminal home so a project switch retains the Agent’s local login. Desktop uses the user's home directory when no project or `cwd` is
specified. Core launches the executable with explicit arguments. Browser
requests refer to profile and project IDs.

Use **ACP chat** in the native app to create or open a session. The device
stores the transcript and the third-party session ID locally. After the Agent
process exits, Core resumes by capability, using `session/resume` first and
`session/load` with history replacement second. If neither is available,
the old transcript is readable and another turn requires a new session.

Agent tool requests go to the client that started the turn. The device applies
the selected permission option to that request. Third-party Agents execute
their own tools with their local process permissions. Messenger does not
advertise ACP client filesystem or terminal callbacks.

## Local command interface

All routes use the per-start local bearer.

- `GET/PUT /api/v1/control/settings` reads and updates device name, opt-in and
  local Agent profiles. Enabling remote control requires a cloud sign-in and
  is scoped to the selected server and account.
- `POST /api/v1/control/commands/{requestId}` takes a UUID and an action.
  `catalog` returns available agents, local projects and device sessions.
  `create` takes `agentId` and optional `projectId` and `writable`.
  `history` takes `sessionId` and optional `before` and returns a page of
  text previews with the next cursor. `setMode` sets `writable` on a
  Messenger session. `prompt` takes `sessionId` and `text`.
- `POST /api/v1/control/commands/{requestId}/permission` takes `optionId`
  from the current permission event. `POST .../{requestId}/cancel` stops
  the corresponding request. Closing its SSE stream also cancels it.
- `PUT /api/v1/control/platform-tools` is the authenticated local client's
  tool availability and MCP declaration snapshot for device-owned turns.
- Android brokers ACP process stdio through the local `acpProcess` event,
  `GET /api/v1/control/processes/{id}/input` (SSE), and
  `POST /api/v1/control/processes/{id}/output` (a JSON `line`, or an empty
  object for EOF). The companion executable runs under Runtime's UID.

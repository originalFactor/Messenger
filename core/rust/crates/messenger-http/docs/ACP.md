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
Messenger Runtime and perform the Agent setup in its terminal. Open **ACP Agents**
in Settings and use the FAB to add a profile manually or import one from the
Messenger Cloud Store. Codex, Claude Code and OpenCode stdio templates are seeded
once. Profiles support description, enable state, command, JSON arguments and
environment, and optional working directory. The wire format is:

```json
[
  {
    "id": "my-agent",
    "name": "My Agent",
    "description": "Project coding assistant",
    "enabled": true,
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
to Runtime. ACP keeps the Runtime terminal home so a project switch retains the Agent’s local login. New ACP sessions always use the selected project's workspace. Core launches the executable with explicit arguments. Browser
requests refer to profile and project IDs.

Select an ACP Agent in **New conversation** inside a locally owned project.
Creating an ACP conversation requires a project; the home picker offers only Messenger Agents. ACP conversations appear beside other conversations and open in
the regular chat area. Rename and delete are supported; their agent/workspace
binding is fixed. Remote Control is configured separately in **Messenger Cloud**. The device
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
  `create` takes `agentId` and optional `projectId` and `writable`; `projectId` is required for ACP agents.
  `history` takes `sessionId` and optional `before` and returns a page of
  text previews with the next cursor. `setMode` sets `writable` on a
  Messenger session. `prompt` takes `sessionId` and `text`.
- `POST /api/v1/control/commands/{requestId}/permission` takes `optionId`
  from the current permission event. `POST .../{requestId}/cancel` stops
  the corresponding request. Closing its SSE stream also cancels it.
- `PUT /api/v1/control/platform-tools` is the authenticated local client's
  tool availability and MCP configuration snapshot for device-owned turns.
  Enabled configurations are passed to new, resumed and loaded ACP sessions;
  existing workers retain the configuration from their startup. The snapshot
  is held in memory and can contain server credentials. Agents without SSE
  support reject session setup when an enabled SSE server is configured.
  Native Core waits for the first successful catalog publication before
  starting or restoring ACP workers or admitting device-owned Messenger turns
  with tools, including remote requests. Cancellation, shutdown, or a timeout
  (10 seconds for ACP setup, 75 seconds for tool discovery) ends that wait; timeout reports
  `platform_catalog_unavailable`. Standalone Core does not wait for a native
  catalog. Publishing only the tool inventory leaves the previous MCP list
  unchanged; an explicit `mcpServers` array, including an empty array, marks
  the first native MCP catalog ready. Device-owned Messenger turns additionally
  wait until `toolsReady` is true, after enabled servers finish their initial
  connection attempts; ACP setup does not wait for tool discovery.
- When Agent delegation is enabled in MCP settings, ACP workers that advertise
  MCP SSE or HTTP support receive a private loopback `Messenger Agents` server. Its
  `messenger_agents` tool lists or calls agents during an active turn. Enabling
  requires restarting the ACP worker; disabling prevents subsequent calls.
  ACP agents without either transport report an error at session setup. Delegated
  ACP sessions do not receive the delegation server, preventing recursive calls.
- Android brokers ACP process stdio through the local `acpProcess` event,
  `GET /api/v1/control/processes/{id}/input` (SSE), and
  `POST /api/v1/control/processes/{id}/output` (a JSON `line`, or an empty
  object for EOF). The companion executable runs under Runtime's UID.

Default adapters: [Codex](https://github.com/agentclientprotocol/codex-acp),
[Claude Code](https://github.com/agentclientprotocol/claude-agent-acp), and
[OpenCode](https://opencode.ai/docs/acp/). Package adapters launch with pnpm;
OpenCode uses `opencode acp`. Merely adding/importing a profile starts no process.

## Agent-provided session settings

The native chat Settings button reads the running Agent's `configOptions`,
including grouped select options, descriptions and boolean toggles. Model,
reasoning and Permissions labels/values come from the Agent; Messenger does not
infer a permission policy from a label. Legacy Agents exposing session modes
use `session/set_mode`. New options in the mode category supersede that legacy
selector. Controls are disabled during a prompt or configuration request.

Core reads options from new/load/resume responses and session notifications,
and sends `session/set_config_option` for changes. A successful response replaces
the full list; errors never optimistically replace the displayed value or retry.
Opening an old session restores the process and reads its actual options.

ACP chats reuse the native message list, avatars, bubbles, Markdown, thought
blocks, tool cards, history paging, copy menus and input layout. Tool/thought
events are persisted with local messages. Individual transcript deletion and
regeneration are hidden because ACP does not expose those operations; session
rename/delete remain available.

Reference: [Zed ACP configuration selectors](https://github.com/zed-industries/zed/blob/0929d569fa3e1b1968b7c486f09c599fe1334587/crates/agent_ui/src/config_options.rs)
and its [Agent session updates](https://github.com/zed-industries/zed/blob/0929d569fa3e1b1968b7c486f09c599fe1334587/crates/agent_servers/src/acp/session_updates.rs).

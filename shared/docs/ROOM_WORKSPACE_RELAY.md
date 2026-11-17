# Room workspace relay boundary

This is the contract for replacing `messenger-remote`. The device SSE host,
session access source and read-only owner check are prototypes; no workspace
executor or production relay is installed. A successful owner snapshot is not
permission to execute an external operation.

## Connection and identity

- A signed-in device maintains one account-scoped `/api/remote/events` stream.
  The server retains only one connection for each device ID, so device control
  and workspace tools must share it. Workspace presence and routing do not
  require the separate, default-off remote-control opt-in.
- Bind the stream, all tool calls and their replies to the same normalized
  server base, account, session generation and device ID. An authenticated 401
  expires only that still-current session. Session replacement or shutdown must
  cancel and join every connection, invocation and callback before Room closes
  or another owner starts.
- The server verifies that a claimed `fromDeviceId` is online in the same
  account; it does not prove that the HTTP sender owns that device ID. Treat it
  as a routing hint, not as an authenticated device principal. Cross-device
  execution is an account-level capability and must never imply a stronger
  peer-identity guarantee.

## Owner-side admission and execution

- Accept only supported workspace tools addressed to this device. Inside a Room
  transaction, check the current Cloud session and confirm the project's stored
  `deviceId` is exactly this device. A null legacy owner is insufficient for an
  inbound relay call. The current `RoomWorkspaceRelayAuthority.prepare` performs
  these checks but releases its read snapshot before returning.
- For edit and create, the owner must have the matching conversation, in that
  project, with `writable=true`. Terminal can change files even when its prompt
  says otherwise, so it also requires a writable matching conversation. Reads
  follow the existing account/project scope. Tool-specific validation and the
  platform's host identity and workspace constraints remain in force.
- Before wiring external execution, introduce a shared gate that makes the
  authorization check and invocation admission atomic with every session,
  project-owner, workspace and writable-mode mutation. All local, sync,
  restore and remote writers must participate. The gate must remain held until
  the platform confirms the operation and any child process have stopped. Do
  not hold an unfenced database transaction across a potentially long external
  call or assume a prior snapshot still authorizes a later side effect.
- `RoomWorkspaceExecutionFence` is an unconnected in-process prototype: it
  serializes durable admission and execution with participating mutations,
  refuses a duplicate sender/call ID, and retains an unresolved reservation
  when an executor throws, reports an unknown outcome or is cancelled. An exact
  Cloud session invalidation cancels registered in-flight work without waiting
  for the fence and blocks subsequent admission until proof-backed recovery;
  a newer session needs a fresh fence instance. A trusted executor may return
  `Stopped` only after all work and children actually stop. The Room owner must
  retain the same fence for its full lifetime. Existing Cloud 401 callbacks,
  sync, project and conversation writers do not yet use it. It cannot protect
  against another database instance, the legacy Core, or an executor that
  asserts stop without proof, and is not installed.
- A token/record must be bound to the session, sender routing ID, call ID,
  project, conversation, tool, arguments and captured workspace. A duplicate
  must never start a second invocation, including after an interrupted or
  ambiguous result. Runtime shell processes share their app UID; a journal
  writable by those processes cannot prove an operation stopped. Unknown stop
  evidence must remain unknown and must not release a reservation.

## Caller and transport

- Refuse a new turn for an offline remote project before transcript mutation.
  Send each tool call at most once; a timeout, lost response or disconnect may
  mean it executed. Send cancellation when possible, retain any reservation
  until the owner has reliable stop evidence, and report uncertain outcomes.
- Presence loss, caller cancellation, logout and session changes terminate
  pending waits and request owner-side cancellation. A tool result must match
  an outstanding call and target; unexpected, duplicate and stale results do
  not complete another invocation. The server does not include an authenticated
  sender in result frames, so a random call ID is correlation, not proof of peer
  identity.
- SSE reconnection is allowed with bounded backoff, but must not replay a tool
  call, cancellation, result, control command or permission. Completed and
  uncertain call identities must survive every reconnect in their owning
  lifetime; cross-process recovery needs an independently trusted durable
  ledger and worker-stop proof before it can safely resume.

Exercise both platforms with a same-account pair, a foreign account, stale
sessions, owner and mode changes racing admission, duplicate frames, dropped
replies, process death and workers that do not stop on cancellation. Keep the
current Rust path until the complete replacement and its lifecycle are verified.

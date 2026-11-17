# Android Core cutover ownership

Production Android still opens Rust Core in `MessengerApplication`. The isolated
Room host cannot replace it until its ownership scope excludes the previous
Core/source users, Runtime and ACP workers, and new Room users for the full
database and asset lifetime. A check that merely observes a quiet instant is
not a fence. The current code does not provide the required platform proof.

The old Core API's `ServerHandle.stop()` cancels its shutdown token, joins the
axum server task, joins the remote-control and scheduler loops started with
that server, and aborts/joins local in-process request tasks registered in
`ApiState.tasks` (turn workers, background replay, control dispatch, remote
client lanes, queued sync and individual scheduled executions). It still does
not prove that every image job, agent relay stream, ACP process, Runtime child
or remote side effect outside the HTTP crate has stopped.
`HttpServerHandle.stop()` delegates to this operation, and the Rust Core handle
separately owns an `Arc<Store>`. Consequently a successful HTTP stop is still
not evidence that all Core tasks and SQLite descriptors are gone. The Android
application currently keeps its server handle and has no complete
shutdown-and-join handoff.

`CoreSourceSqliteLease` holds `BEGIN IMMEDIATE` and validates SQLite source
and sidecar identities. It is an additional database consistency guard, not a
process-lifetime fence: it does not stop provider calls, Runtime work, asset
writers or same-process descriptors, and it must not be treated as proof that
old workers cannot resume. The Android cooperative Room owner also excludes
only other participants in its own protocol; older Core versions do not take
that owner marker.

The Runtime companion has a separate UID. Its current MCP/ACP registries
track a direct child, wait for launch and close, and fence callbacks during
service replacement. Android reports lost Binder sessions as unverified.
These mechanisms do not contain arbitrary child descendants, which can outlive
the direct process. A signature-protected Binder or an app-private file is not
an independent authority against programs executing under the Runtime UID.
In particular, a Binder death, direct-child exit, empty session registry or
package-upgrade observation cannot establish that all earlier Runtime work has
stopped. The disposable Android upgrade observation in `androidApp/AGENTS.md`
is not a guarantee across devices, installation methods or descendants.

A candidate production handoff must satisfy all of these requirements:

1. Exclude every older Core instance and source, SQLite sidecar and asset user
   until the new Room owner is released. Stop and join every Core task that can
   use those objects; a listener shutdown alone is insufficient.
2. Establish a real containment or authority boundary for Runtime and ACP work,
   including descendants and prior process generations. Verify the boundary on
   supported Android versions and OEMs. If the platform cannot provide that
   proof for older workers, retain an explicit unresolved recovery state.
3. Prevent new work while ownership changes, retain any unknown side effect or
   unacknowledged call without automatic replay, and keep originals and backup
   evidence. A user acknowledgement may record uncertainty; it does not prove
   a remote or detached operation stopped.
4. Reconcile old Room databases and their sidecars explicitly, including the
   projects and Provider protocol fields omitted by the historical importer.
   See `shared/docs/LEGACY_ROOM_RECOVERY.md`. Never infer that a remaining old
   file was fully imported from the Rust import marker.
5. Keep the proof valid through failure, cancellation, shutdown and process
   death, and establish an independent recovery procedure before releasing a
   durable owner marker. Test interrupted migrations, competing processes,
   active tasks and restored backups on real supported devices.

Possible designs include a separately protected worker authority, or an
OS-backed worker containment mechanism whose admission, descendant lifecycle and
termination guarantees are demonstrably available to the unprivileged Runtime.
Neither is implemented here; process groups, direct-child wait and a shell-owned
journal alone are insufficient. Installing a new implementation also cannot
retroactively contain children already launched by an older version.

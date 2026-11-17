# Kotlin Core migration and build-cost plan

The intended destination is a Kotlin-owned Android/Desktop application, with Room
for local records and `llm-typewriter` rendering Markdown and LaTeX. The current
production application still starts the Rust Core; the Kotlin code in `shared` is
an isolated migration prototype, not a completed replacement. Do not remove the
Core, its bindings, or existing stores until the cutover conditions below are met.

## Cost baseline

- Android debug supports both `arm64-v8a` and `x86_64`. A developer can build one
  target with `-Pmessenger.debugAbis=arm64-v8a` or `x86_64`; the default keeps
  physical-device and emulator support. Release and CI have separate ABI settings.
- In one local dual-ABI debug APK, the two `libmessenger_ffi.so` files occupied
  32.6 MiB and the two bundled SQLite libraries 2.4 MiB. These are APK entries,
  not a release-size estimate: release uses R8, a different native profile and one
  ABI. Runtime's separate debug APK contained a 61.3 MiB bootstrap entry; removing
  Core alone will not remove that cost.
- The retired TUI was not part of native-client CI. The major client build saving
  comes when Core and its Cargo, bindgen, NDK and native packaging steps actually
  leave those builds.
- Room is Kotlin-facing, but the current `sqlite-bundled` driver includes native
  libraries. Desktop Compose and the separate system-WebView helper also have
  native components. A Kotlin Core does not by itself make the whole product
  native-free. Evaluate drivers separately on each platform against required SQLite
  features, migration behavior, supported versions, build time and artifact size.
- The current prototype adds Room/KSP while production still builds Rust. It is
  an intermediate development state and can increase build cost until cutover;
  it must not be presented as the achieved optimization.

For comparisons, record the commit, machine/runner, ABI, Gradle and Cargo cache
state, task, elapsed time and output size. Measure a clean build, a warm no-change
build and a representative Kotlin-only edit. Compare the same release variant and
ABI before and after cutover; do not extrapolate release size from debug APKs.

## Cutover sequence

1. Inventory Core routes, SSE events, storage tables, background jobs, tools,
   ACP, learning, cloud sync and asset ownership against the Kotlin implementation.
   Keep unsupported behavior on Core until its replacement is complete and tested.
2. Finish Kotlin repositories and turn orchestration with behavioral parity,
   including default-Agent and model-required invariants, visible errors, explicit
   cancellation, safe tools, cloud session expiry and background task lifetime.
   Exercise normal UI flows on both Android and Desktop, not only isolated units.
3. Finish source-writer exclusion, Room ownership and recovery on each supported
   platform. Import a consistent Core v9 snapshot to a separate Room database,
   preserve the original database and owned assets, and fail closed on ambiguous
   or interrupted state. Validate upgrades, process interruption, backup/restore,
   and recovery without losing credentials or user records. Android emulator
   tests do not establish power-loss behavior or Windows and ARM-device behavior.
4. Switch application startup and dependency injection only after feature parity
   and safe cutover are demonstrated. Test existing installations and new installs,
   including crash/restart, cloud and learning data, attachments and tools.
5. Retire Core and bindings, remove their build and CI setup and stale docs,
   then measure the same build and release variants again. Keep the independent
   Desktop WebView helper until its separate use case has a replacement.

The experimental Runtime tracked-workspace journal shares a UID with shell
processes and cannot provide trustworthy proof that an operation stopped. Its
protocol must remain unavailable for production recovery until a trustworthy
ownership and stop mechanism exists. The isolated Room migration also requires a
real platform ownership gate; successful data-copy tests do not establish one.
The fresh and leased startup prototypes retain Room and ownership until the
host's registered stop-and-join actions succeed, including after callback
failure; the leased path also retains the source lease. A production host must
provide and verify those stop actions; retaining an ownership marker alone does
not prove that workers have stopped.
Restore handling must also detect a Room database and asset directory restored
from different points in time, even if the original Core inode and publication
receipt still match. Under exclusive ownership, validate the restored database's
current asset references and their hashes before any restore rebind. This is an
explicit restore check, not a requirement to scan every asset on normal startup.

Image quota reservations now use a durable job-to-file journal and proof-gated
reconciliation in the isolated Kotlin service. This removes one class of
quota-counted orphan files after a normal or interrupted process, but it does
not replace the platform owner proof, Windows/Android directory durability
validation or cross-process file exclusion required for production cutover.

Restore preflight now has an explicit proof-gated finalization protocol: a
durable rebind receipt binds the original publication identity, the exact
current Core source identity and the logical source content hash before the
pending marker is removed. Startup and paired migration verify that the
external marker and Room receipt agree with the held source lease. The
authority remains caller-supplied; no production owner or cross-platform
crash/power-loss validation is installed.

The current Kotlin backend slice exercises Room-backed chat, selected local tools,
optional scheduling, Skills and image jobs in isolation. It is not an AppBackend
and is not selected by Android or Desktop. Cutover still needs platform ownership
and recovery proofs, ACP and Runtime integration, generated-app execution,
workspace relay, migrated-store clear-all, complete Cloud/session wiring, and
end-to-end validation. Unsupported slice paths must remain visible failures until
their replacements are complete. Windows storage has additional open issues in
[WINDOWS_ROOM_STORAGE.md](WINDOWS_ROOM_STORAGE.md).

## Renderer and submodule

The root repository embeds the maintained `llm-typewriter` submodule and uses
Compose LaTeX. Publish the submodule commit before publishing a parent commit that
references it, so a fresh recursive checkout and CI can resolve the gitlink. Keep
dependency versions under the parent build's catalog and verify Android/Desktop
rendering, including streaming math and font packaging, when upgrading it.

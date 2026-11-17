# Rust Core dependency audit

This is a retirement checklist, not permission to delete the Core. Run
`bash .github/scripts/audit-rust-core-dependencies.sh` immediately before the
planned one-commit removal and attach the output to the cutover review. Use
`--strict` for a machine-readable pass/fail gate: it exits non-zero while
production startup, Gradle bindings, native packaging or Rust CI references
remain. The script prints `[summary]` with `blocking_references`,
`rust_workspace` and `native_bindings`; `--summary FILE` writes the blocking
count. CI runs `bash .github/scripts/test-audit-rust-core-dependencies.sh`
alongside the client-selection fixtures, covering both the current blocked
state and an empty switched tree. A clean result still requires every listed
area to contain no production dependency and the remaining historical
references to be intentional.

## Current state

Production Android and Desktop still call `CoreHandle.open`, start the loopback
server, install `RestCoreBridge` and construct the default Rust-backed
`AppContainer`. `KotlinRoomBackendSlice.asAppBackend()` remains isolated and
refuses ACP owners and remote-device hosts. Do not remove `core/rust`, either
binding module, native packaging or Cargo CI setup before those startup paths
are switched to a proven complete `AppBackend`.

The audit script scans these areas:

- application source, including Wear bridge and Runtime AIDL users;
- Gradle module includes, native binding tasks, dependency declarations,
  ProGuard/consumer rules and AIDL copies;
- CI workflows, build selection scripts and release/version tooling;
- module and root documentation, including route/event contracts and storage
  migration notes;
- generated native artifacts such as `libmessenger_ffi.so` and SQLite JNI
  libraries.

`core/rust/crates/messenger-http/openapi.yaml`, `docs/SSE.md`, `docs/ACP.md`,
`docs/REMOTE.md` and `docs/ASSETS.md` remain authoritative until all callers are
retired. The Cloud server relay contracts mirror those routes. Keeping the
workspace relay v2 disabled and Rust-owned routes in sync is part of the
prerequisite, not an optional cleanup.

## Cutover evidence still missing

- A real Android/Desktop owner that excludes every old Core/source, SQLite
  sidecar, asset and competing Room writer for the full database lifetime.
- A real Kotlin/platform ownership boundary that excludes old Core instances,
  SQLite sidecars, assets, Runtime/ACP descendants and remote side effects for
  the full lifetime. Rust HTTP-listener shutdown and in-process task joins are
  not platform stop proof; do not close this gap by extending Rust Core.
- Complete ACP, MCP, Cloud takeover, remote attachments, workspace relay,
  scheduled-task and generated-application parity; explicit visible failures
  for anything not installed.
- Reliable old Room v20/v21/v22, WAL, conflict, deletion, interruption,
  backup/restore and multi-device recovery, plus safe migrated-store clear-all
  that preserves or deliberately retires Core source and credentials.
- Platform storage proofs for Windows ACLs, link/path handling and durable
  publication; Android and Desktop end-to-end installation/upgrade tests.
- Post-switch source, Gradle, CI, AIDL, ProGuard, documentation, version script
  and native-artifact scan, followed by all-client builds and targeted device
  tests.

Wine 11.21 successfully performed a simple file create/read/delete check.
`icacls`, hard-link and file-ID probes were unavailable or unsupported in Wine,
so that result is not evidence for NTFS ACLs, handle identity, link behavior or
durable publication. Real Windows validation remains required.

Only after these items are proven may the final separate commit remove the Rust
Core and update the relevant `AGENTS.md` files.

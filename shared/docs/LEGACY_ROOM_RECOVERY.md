# Legacy Room recovery

Old Room data cannot be considered fully imported just because the Rust Core
store has a `legacy_import_done` marker. The migration in
`core/rust/crates/messenger-store/src/import.rs` records `none:<time>` when it
finds no old database and `v1:<time>` after its import. Neither marker identifies
the source file or proves that a currently present old file was included.

There is also a concrete gap in the historical importer. The Room database
immediately before the Rust cutover (`b43fc15^`) was schema version 22; its
20→21 migration introduced a `projects` table and `conversations.projectId`.
The Rust importer copies providers, models, agents, conversations and messages,
but does not copy projects and sets each imported conversation's project ID to
null. Thus a legacy v21/v22 file can contain projects and associations absent
from Core even when the Core marker says `v1`. The old v22 schema also stored
`providers.apiFormat`, but the importer assigns `chat` instead of copying it;
other protocol selections can consequently be lost. These gaps are established
from source history; they do not mean every installation has affected records.

After import, either dataset may have diverged: a project or conversation missing
from Core may have been deliberately deleted, and matching IDs can have newer
edits. Automatically copying missing rows or overwriting Core values can revive
deleted data or discard later changes. A safe recovery flow needs to keep the
original database and its SQLite sidecars, read a consistent WAL-aware view while
all old writers are excluded, show both datasets and conflicts, and apply only
explicitly selected changes under the destination's normal ownership and sync
rules. Include project workspace paths, conversation associations and all other
legacy fields in the comparison; do not infer a complete import from row counts,
timestamps or the marker alone. A backup must also include related local assets
and preserve credentials privately.

The isolated Android Room host currently refuses a fresh or Core-based cutover
when either historical Room filename or a WAL, SHM or rollback-journal sidecar
is present. This preserves the evidence but is not a recovery UI and does not
reconcile the two databases. Production still starts Rust Core. The eventual
recovery path needs device tests with old v20/v21/v22 stores, committed WAL,
conflicting edits and deletions, interrupted recovery and source preservation,
and independent platform proof that old writers cannot resume.

`CoreSourceSqliteLease.previewLegacySnapshot` offers an isolated, bounded
comparison of selected fields for v21/v22 snapshots. It reports both sides of
each project (including workspace, timestamps and Core device ownership),
conversation project association, and provider name/protocol. A missing v21
protocol and legacy device ownership are reported as unknown. Equal selected
fields do not establish that the datasets are reconciled. The API does not read
provider keys, infer deletions or apply changes; v20 and malformed schemas fail
explicitly. Before opening the snapshot, it rejects aliases to the leased Core
database or sidecars, symlinks and hard links. The caller must hold the Core
source lease, provide a separate WAL-aware scratch snapshot, and exclude old
writers and path replacement throughout. Opening the original legacy database
read-only can still use its WAL/shared-memory files, so this API does not itself
acquire or preserve a source snapshot. A future device flow must acquire and
validate that snapshot, present conflicts to the user and revalidate source and
destination identities before applying explicitly selected recovery actions.

`LegacyRoomRecoveryApply` is an isolated apply component, not a production
entry point. It displays the selected legacy/Core fields alongside the current
Room values, excluding provider credentials. The caller supplies a scoped
provenance verifier that proves the scratch copy's original identity and holds
writer and path exclusion from review through commit; no platform verifier or
recovery UI exists yet. Review is cancellable and holds no Room writer
transaction. Explicit project, association and provider-format selections are
revalidated together before a single Room transaction applies them and queues
cloud sync. A selected Room row differing from the displayed Core baseline or
changing during review is rejected; absence never implies deletion intent.
Projects with a non-null foreign device ID cannot be modified or associated;
an explicitly selected legacy project with null ownership retains that value.
Creating a missing project requires confirmation of the current device ID.
Both original databases must remain untouched; the caller must provide a
separate consistent scratch snapshot. This partial field recovery cannot prove
the datasets are fully reconciled or make production cutover safe.

`LegacyRoomRecoverySession` supplies an offline review entry that composes that
apply operation with a private byte-for-byte copy of the legacy database and
its durable WAL and rollback journal. It validates paths and inode aliases
before opening Core, rejects unsafe source links, bounds the combined copy to
1 GiB, and rechecks original identity and content after the user's selection.
SQLite opens only the scratch copy; both original stores remain in place. The
caller must hold a real lifetime ownership scope that excludes all writers,
checkpointers, old processes and path replacement throughout copying, review
and commit. No current production host supplies that proof. The private scratch
contains the original credentials and conversation data: it is never included in
the preview and is deleted after success, cancellation or ordinary failure. A
process crash can leave a private staged copy requiring verified offline
cleanup; do not automatically delete an unverified path or treat the copy as an
ownership or source identity proof.
Nonempty rollback journals are rejected before opening the scratch database;
they require separate offline recovery because a read-only SQLite open cannot
be relied on to recover a hot journal. The original journal is retained.

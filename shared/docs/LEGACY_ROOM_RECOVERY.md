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

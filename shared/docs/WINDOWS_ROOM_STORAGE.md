# Windows Room storage assessment

The current Kotlin storage migration is not ready for Windows. Do not enable it
or substitute weaker checks just because a file operation succeeds under Wine or
on a Windows CI runner. Production Desktop continues to use Core.

## Current failures

- Both fresh and migrated Room startup call `requireRoomPublicationParent`,
  which requires POSIX permissions, and `syncRoomPublicationDirectory`, which
  expects a directory `FileChannel` that can be forced. Desktop publication
  also uses a hard link. The paired cutover and fresh initializer create files
  and directories with POSIX attributes. These are separate requirements; changing
  just the parent check does not make the sequence safe on Windows.
- The held Core source lease checks `unix:nlink` on the database and WAL shared
  memory. Both Room ownership prototypes require Unix attributes and private,
  parent-synced state. A process-local mutex cannot replace their lifetime and
  crash-recovery guarantees.
- Desktop asset publication requires a POSIX-private root, a
  `SecureDirectoryStream`, directory sync and hard-link creation. The portable
  asset reader's hash and before/after identity checks do not establish Windows
  ACLs, prevent all directory replacement races, or make writes durable. Image
  generation also needs its quota reservations and asset writes to succeed
  before sending a billable request.

Failures must happen before a Room owner is exposed, a paid request is sent or
original data is removed. Network shares, redirected profiles, unsupported
filesystems and unverifiable ACLs need an explicit unsupported result until they
have their own proof and tests.

## Candidate: transactional in-place migration

The historical Core v9 fixture and the currently generated Room v1 schema have
the same column metadata and foreign keys for all 12 domain tables. Room adds
`index_conversations_projectId`. In a local Linux experiment, adding that index,
Room's generated identity table and schema version in one SQLite transaction
preserved a seeded default Agent and KV value; terminating the writing process
before commit rolled the schema changes back. The experiment compared SQLite
column, foreign-key and index metadata with generated Room DDL, and used a
temporary copy. It did not open the result with Room, cover other Core schema
variants, test Windows, or simulate power loss. It is not a migration.

Separately, `CoreInPlaceRoomProbeTest` passed on Linux using a temporary Core v9
fixture, the actual Room database and `BundledSQLiteDriver`. A test-only 9→1
migration adds the missing index and lets Room validate the schema and write
its own identity; the successful case reads the existing provider credential,
session, learning row, default Agent and equal-timestamp message order. For a
throwing migration and an unexpected extra column, the tests verify that the
source version and session remain and the added index rolls back. The deliberate
downgrade exists only because the isolated Room schema is currently v1;
production must use a newer version.
These tests do not verify a real Windows filesystem, live old-Core exclusion,
backup/restore, crash recovery or power-loss behavior.

A possible next prototype would preserve the existing database and asset paths,
use a supported Room migration from Core v9 to a newer Room schema version, and
let Room create and verify its own schema identity. A Room migration callback
runs inside a transaction and Room validates the resulting schema; see the
[Room Migration reference](https://developer.android.com/reference/kotlin/androidx/room/migration/Migration)
and [migration guide](https://developer.android.com/training/data-storage/room/migrating-db-versions).
Do not hard-code the generated identity hash or set it before independent schema
validation. Existing Room v1 prototype stores would need their own migration
path, and unknown columns, tables, triggers or source versions must fail closed.

This approach reduces database and asset relocation during cutover, but is not
itself a complete solution. It still needs a separately verified recoverable
backup; a proof that every old Core and new Room writer and asset user is stopped
and cannot restart; authorization reset where a backup is restored; and a
durable, process-wide recovery policy. It also does not solve new asset
publication or Windows filesystem access. Validate SQLite's actual driver,
journal mode and synchronous settings against its documented
[atomic commit](https://www.sqlite.org/atomiccommit.html) and
[WAL](https://www.sqlite.org/wal.html) guarantees; successful process-kill
tests alone do not establish power-loss behavior.

## Windows primitives and verification

An eventual Windows adapter needs to establish all of the following together:

1. Hold a trusted lifetime owner across startup, workers and shutdown; after a
   crash, keep recovery blocked until independent evidence proves old work has
   stopped. A named mutex used only by the new app cannot exclude an old Core
   binary that never takes it.
2. Establish private, inherited ACLs and effective access for the entire trusted
   directory chain. Exclude untrusted writers and handle reparse points, junctions,
   path replacement, aliases and hard links. Use handle-based identity including
   volume and file ID and establish link count; a path comparison or checking
   only `isSymbolicLink` is insufficient. Windows documents directory opening and
   reparse-point flags in [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)
   and handle identity in [FILE_ID_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-file_id_info).
3. Publish database, owned assets and any recovery marker without overwriting
   unrelated files, and establish the required content **and namespace**
   durability after interruption. [FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers)
   documents file flushing and requires a writable handle; it does not by itself
   establish a supported non-administrative parent-directory durability protocol.
   Do not treat `MOVEFILE_WRITE_THROUGH` or a successful directory open as proof
   of the entire multi-file publication contract.
4. Fail closed before side effects when any guarantee cannot be established.
   Keep source data and ambiguous staged files for explicit recovery; never
   silently retry an ambiguous image request.

Test on actual Windows NTFS with a standard user, including a Windows CI job,
not only Wine. Cover existing v9 and fresh installations, committed WAL data,
all schema variants, seeded credentials and learning/assets, interruption at
each migration/publication step, old-Core and second-Room concurrency,
symlink/junction/hard-link and parent replacement attempts, ACL denial,
disk-full and failed-flush cases, paths with spaces and non-ASCII characters,
reboot/restore, and offline recovery. Include a real Room open and the user
flows after migration. Test crash durability on a controlled Windows VM or
filesystem fault setup separately; an ordinary CI success cannot prove it.

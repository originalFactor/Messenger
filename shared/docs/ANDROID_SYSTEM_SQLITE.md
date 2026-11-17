# Android system SQLite assessment

The experimental Kotlin Room backend currently uses AndroidX `sqlite-bundled`
2.7.0 for both Room and migration. It therefore still packages native SQLite;
retiring Rust alone does not eliminate native libraries. On this checkout the
x86_64 debug APK contains an uncompressed 1,189,688-byte `libsqliteJni.so`;
the cached arm64 dependency contains a 1,281,456-byte library before any release
processing. These are individual library sizes, not measured APK or download
savings, and are not a release comparison.

Android has a system SQLite implementation and AndroidX provides
`AndroidSQLiteDriver`, but substituting it for `BundledSQLiteDriver` needs more
than changing the dependency:

- The AndroidX system driver's `open(path)` uses `openOrCreateDatabase`, while
  migration explicitly opens existing source databases read-only or read-write
  without creation. Preserve these modes, missing-file failure, and the
  source/destination separation checks.
- The framework adapter maps transaction commands to Android's thread-bound
  SQLite session. The current source lease holds a transaction across suspending
  work and the Room lifetime. A dispatcher thread change can invalidate the
  assumption that the same session owns that transaction. An Android-specific
  lease with a proven lifetime and no pooled connection handoff is required.
- The framework adapter uses Android cursors and cursor windows. ACP transcripts
  and other KV records can be multiple MiB; verify large text and blobs rather
  than assuming the system adapter can read all existing rows.
- WAL management and `PRAGMA journal_mode` are mediated by the Android SQLite
  connection pool. Verify source snapshots, checkpoints and publication against
  the actual driver instead of transferring the bundled driver's assumptions.

A useful next step is an isolated device probe, leaving production and migration
on the existing driver. Test API 30 and current Android on real devices or
representative emulators: missing-file/read-only behavior, a suspended held
lease with thread switches and a competing writer, committed WAL data, large
text/blob round trips and migration fingerprints, checkpoint/reopen, process
termination and recovery. Android's system SQLite version varies by OS and may
also vary by vendor. Only after these checks and a safe lease implementation
should the Android dependency be split from Desktop and APK/build measurements
be repeated. Desktop would still need a SQLite implementation; system SQLite on
Android is native OS code even if no SQLite binary is shipped in the APK.

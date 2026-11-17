/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Evidence about source contents only; it never authorizes a restore or opens Room for use. */
internal sealed interface CoreRestoreEvidence {
    data object LegacyMarker : CoreRestoreEvidence
    data class ContentMatch(val publicationId: String, val previousIdentity: String, val contentHash: String) : CoreRestoreEvidence
    data object ContentChanged : CoreRestoreEvidence
}

/**
 * An isolated preflight for a possible future restore flow. The caller must already hold the
 * source lease and a platform-verified fence proving all old and new owners/workers stopped.
 * A content match alone cannot establish that another process is not writing a replaced inode,
 * that all backup assets exist, or that device-local grants have been revoked. v1 markers lack
 * a separate content hash and are explicitly unprovable here. No rebind or mutation occurs.
 */
internal object CoreRestoreAssessment {
    fun assess(lease: CoreSourceSqliteLease, destination: Path): CoreRestoreEvidence {
        lease.requireDistinctDatabasePaths(destination)
        require(Files.isRegularFile(destination, NOFOLLOW_LINKS)) { "Restored Room database is missing or invalid" }
        val markers = BundledSQLiteDriver().open(destination.toString(), SQLITE_OPEN_READONLY).use { db ->
            db.prepare("PRAGMA quick_check").use { rows ->
                check(rows.step() && rows.getText(0) == "ok" && !rows.step()) { "Restored Room integrity check failed" }
            }
            db.prepare("SELECT key, value FROM kv WHERE key IN (?, ?, ?, ?)").use { rows ->
                rows.bindText(1, CoreStoreMigration.MARKER_KEY)
                rows.bindText(2, CoreStoreMigration.SOURCE_FINGERPRINT_KEY)
                rows.bindText(3, CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY)
                rows.bindText(4, ROOM_PUBLICATION_ID_KEY)
                buildMap { while (rows.step()) put(rows.getText(0), rows.getText(1)) }
            }
        }
        check(markers[CoreStoreMigration.MARKER_KEY] == CoreStoreMigration.MARKER_VALUE) { "Restore migration marker is missing" }
        val publication = checkNotNull(markers[ROOM_PUBLICATION_ID_KEY]) { "Restore publication ID is missing" }
        val previousIdentity = checkNotNull(markers[CoreStoreMigration.SOURCE_FINGERPRINT_KEY]) { "Restore source identity is missing" }
        check(publication.matches(Regex("[0-9a-f]{32}")) && previousIdentity.matches(Regex("[0-9a-f]{64}"))) {
            "Restore identity marker is invalid"
        }
        val saved = markers[CoreStoreMigration.SOURCE_CONTENT_FINGERPRINT_KEY] ?: return CoreRestoreEvidence.LegacyMarker
        check(saved.matches(Regex("[0-9a-f]{64}"))) { "Restore content marker is invalid" }
        return if (saved == lease.fingerprint()) CoreRestoreEvidence.ContentMatch(publication, previousIdentity, saved)
        else CoreRestoreEvidence.ContentChanged
    }
}

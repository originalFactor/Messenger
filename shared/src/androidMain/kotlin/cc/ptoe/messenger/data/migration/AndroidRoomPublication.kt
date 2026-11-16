/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.nio.file.Path

/** Experimental adapter; no production lifetime gate or callers are installed. */
internal object AndroidRoomPublication {
    // Fresh seeding or Core import must insert this ID in its existing transaction alongside the
    // completion marker. Until that change is made, publication deliberately fails closed.

    suspend fun publish(
        staged: Path,
        target: Path,
        receipt: Path,
        transactionId: String,
        verifyRoom: suspend (Path) -> Unit,
    ) {
        AndroidRoomFiles.publishExclusive(staged, target, receipt, transactionId) { path, expected ->
            checkIntegrityAndId(path, expected)
            // This callback must validate the exact database path, the Room schema and the default
            // Agent invariant, then close its Room handle before it returns.
            verifyRoom(path)
            checkIntegrityAndId(path, expected)
            CoreStoreCutover.finalizeDatabase(path)
        }
    }

    suspend fun verifyExisting(target: Path, receipt: Path, verifyRoom: suspend (Path) -> Unit) {
        AndroidRoomFiles.verifyExisting(target, receipt) { path, expected ->
            checkIntegrityAndId(path, expected)
            verifyRoom(path)
            checkIntegrityAndId(path, expected)
        }
    }

    private fun checkIntegrityAndId(path: Path, expected: String) {
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY).use { db ->
            db.prepare("PRAGMA integrity_check").use { result ->
                check(result.step() && result.getText(0) == "ok" && !result.step()) { "Room integrity check failed" }
            }
            db.prepare("SELECT value FROM kv WHERE key = ?").use { statement ->
                statement.bindText(1, ROOM_PUBLICATION_ID_KEY)
                check(statement.step() && statement.getText(0) == expected && !statement.step()) {
                    "Room publication ID is missing or does not match its receipt"
                }
            }
        }
    }
}

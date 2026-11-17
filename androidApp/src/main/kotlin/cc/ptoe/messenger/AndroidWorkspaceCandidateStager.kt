/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import cc.ptoe.messenger.workspace.WorkspaceArchive
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unconnected candidate staging: caller must explicitly authorize the archive descriptor. The
 * Runtime UID cannot access this private destination. Old Core code sharing the app UID can still
 * access it. This does not authenticate the source, make its contents a coherent snapshot, or
 * authorize publishing it as a live workspace.
 */
internal class AndroidWorkspaceCandidateStager(private val context: Context) {
    internal class Candidate internal constructor(internal val directory: Path) {
        override fun toString(): String = "WorkspaceCandidate(redacted)"
    }

    /** Retains incomplete candidates on failure. The caller retains ownership of [archive]. */
    suspend fun stageAuthorizedArchive(archive: ParcelFileDescriptor): Candidate = withContext(Dispatchers.IO) {
        val files = context.filesDir.toPath().toAbsolutePath().normalize()
        val parent = Os.lstat(files.toString())
        require(OsConstants.S_ISDIR(parent.st_mode) && parent.st_uid == Os.getuid()) { "App files directory is unavailable" }
        val base = files.resolve("workspace-candidates")
        try { Os.mkdir(base.toString(), 448) } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EEXIST) throw failure
        }
        val before = privateDirectory(base)
        val target = base.resolve(UUID.randomUUID().toString())
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(archive.fileDescriptor)).use { input ->
            WorkspaceArchive.import(input, target)
        }
        val after = privateDirectory(base)
        check(before.st_dev == after.st_dev && before.st_ino == after.st_ino) { "Candidate directory changed" }
        Candidate(target)
    }

    private fun privateDirectory(path: Path) = Os.lstat(path.toString()).also {
        require(OsConstants.S_ISDIR(it.st_mode) && it.st_uid == Os.getuid() && (it.st_mode and 63) == 0) {
            "Candidate directory is not app-private"
        }
    }
}

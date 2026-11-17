/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import cc.ptoe.messenger.workspace.WorkspaceArchive
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** Export requires a previously authorized source descriptor; import stays unpublished staging. */
internal object WorkspaceTransfer {
    internal const val MAGIC = WorkspaceArchive.MAGIC
    internal const val END = WorkspaceArchive.END
    internal const val DIRECTORY = WorkspaceArchive.DIRECTORY
    internal const val FILE = WorkspaceArchive.FILE
    internal const val MAX_ENTRIES = WorkspaceArchive.MAX_ENTRIES
    internal const val MAX_DEPTH = WorkspaceArchive.MAX_DEPTH
    internal const val MAX_FILE_BYTES = WorkspaceArchive.MAX_FILE_BYTES
    internal const val MAX_TOTAL_BYTES = WorkspaceArchive.MAX_TOTAL_BYTES

    fun export(source: FileDescriptor, output: OutputStream) = AndroidWorkspaceExporter.export(source, output)
    fun import(input: InputStream, destination: Path) = WorkspaceArchive.import(input, destination)
    fun validateRelative(name: String) = WorkspaceArchive.validateRelative(name)
}

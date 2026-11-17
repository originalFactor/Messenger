/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

/** Platform-owned ACP stdio channel. The host must prove process and side-effect shutdown. */
interface AcpProcessChannel {
    val command: String
    val arguments: List<String>
    val envJson: String?
    val cwd: String
    suspend fun output(line: String)
    suspend fun readInput(write: suspend (String) -> Unit)
}

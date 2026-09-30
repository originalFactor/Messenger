/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.domain.tool

/** Combined stdout/stderr text and the shell process exit code. */
data class ShellResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean = false
)

/**
 * Executes a shell command on the host platform: Android routes through the
 * targetSdk-28 companion runtime app (the only way to exec binaries in app
 * data under Android 10+ W^X), desktop uses Windows PowerShell on Windows
 * and /bin/sh elsewhere. [timeoutMs] bounds the whole execution; the process
 * is destroyed when the timeout fires or the caller's coroutine is cancelled.
 *
 * [workingDir] overrides the process working directory (Android: the
 * companion runtime's workspace); a missing directory falls back to it.
 * [onOutput] receives incremental merged output chunks as they arrive, in
 * addition to the final [ShellResult.output] (null = collect only).
 */
expect suspend fun executeShellCommand(
    command: String,
    timeoutMs: Long,
    workingDir: String?,
    onOutput: ((String) -> Unit)?
): ShellResult

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

/**
 * Bridge to an out-of-process shell runtime host: the targetSdk-28
 * Messenger Runtime companion app, whose legacy untrusted_app_27 SELinux
 * domain retains the execute/execute_no_trans rights on app data that the
 * main app's targetSdk-29+ domain lost to the Android 10+ W^X rule.
 * androidApp registers an AIDL-backed implementation at startup. There is
 * deliberately NO fallback shell: without the companion the terminal shows
 * a not-installed state and Android registers no agent tools.
 */
interface ShellRuntimeBridge {
    /**
     * Prepares the runtime (installs the bootstrap if needed). Returns the
     * workspace path, or null when the runtime is unavailable — the caller
     * then falls back to the system shell.
     */
    suspend fun ensureRuntime(): String?

    /**
     * Executes a command and returns the result, or null when the runtime is
     * unavailable (caller falls back). Output chunks stream via [onOutput].
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long,
        workingDir: String?,
        onOutput: ((String) -> Unit)?
    ): ShellResult?

    /** True after a successful [ensureRuntime] — commands will route here. */
    fun isActive(): Boolean

    /** Whether the companion app is installed (cheap PackageManager check). */
    fun isInstalled(): Boolean
}

/** androidApp registers the AIDL client here during application startup. */
object ShellRuntimeRegistry {
    @Volatile
    var bridge: ShellRuntimeBridge? = null
}

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

package cc.ptoe.messenger

import android.content.Context
import android.util.Log
import cc.ptoe.messenger.core.AgentEvent
import cc.ptoe.messenger.core.AgentEventSink
import cc.ptoe.messenger.core.CoreHandle
import cc.ptoe.messenger.core.StoreChangeEvent
import cc.ptoe.messenger.core.StoreChangeListener
import cc.ptoe.messenger.core.corePing
import cc.ptoe.messenger.core.coreVersion
import cc.ptoe.messenger.core.echoEvents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * M1 walking-skeleton probe: proves the real Rust core loads — opens the
 * SQLite store, imports the legacy Room database on first run, subscribes
 * to store changes, and lists conversations. Temporary scaffolding — the
 * repository facades replace it in M2.
 */
object RustProbe {
    private const val TAG = "RustProbe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run(context: Context) {
        scope.launch {
            runCatching {
                Log.i(TAG, "corePing=${corePing()} coreVersion=${coreVersion()}")
                echoEvents(
                    count = 10u,
                    sink = object : AgentEventSink {
                        override fun onEvent(event: AgentEvent) {
                            when (event) {
                                is AgentEvent.TextDelta -> Log.i(TAG, "delta: ${event.text}")
                                is AgentEvent.Finished -> Log.i(TAG, "finished")
                            }
                        }
                    },
                )
                openCore(context)
            }.onFailure { Log.e(TAG, "rust probe failed", it) }
        }
    }

    private fun openCore(context: Context) {
        val storeDir = File(context.filesDir, "messenger_core").apply { mkdirs() }
        val legacyDir = context.getDatabasePath("messenger_database").parentFile
        val core = CoreHandle.open(
            storePath = File(storeDir, "store.db").absolutePath,
            legacyDbDir = legacyDir?.takeIf { it.exists() }?.absolutePath,
            nowMs = System.currentTimeMillis(),
        )
        core.subscribe(object : StoreChangeListener {
            override fun onChange(event: StoreChangeEvent) {
                Log.i(TAG, "store change: ${event.kind} ${event.ids}")
            }
        })
        val conversations = core.listConversations()
        Log.i(TAG, "conversations=${conversations.size}")
        conversations.take(3).forEach {
            Log.i(TAG, "  - \"${it.title}\" agent=${it.agentId} last=${it.lastMessage ?: "-"}")
        }
        val first = conversations.firstOrNull()
        if (first != null) {
            val messages = core.listMessages(first.id)
            Log.i(TAG, "messages in first conversation=${messages.size}")
        }
    }
}

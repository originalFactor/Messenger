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

import android.util.Log
import cc.ptoe.messenger.core.AgentEvent
import cc.ptoe.messenger.core.AgentEventSink
import cc.ptoe.messenger.core.corePing
import cc.ptoe.messenger.core.coreVersion
import cc.ptoe.messenger.core.echoEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * M0 walking-skeleton probe: proves the Rust core library loads and streams
 * events across the UniFFI boundary on a real device. Temporary scaffolding —
 * removed once the real core wiring lands in M2.
 */
object RustProbe {
    private const val TAG = "RustProbe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run() {
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
                Log.i(TAG, "echo stream complete")
            }.onFailure { Log.e(TAG, "rust probe failed", it) }
        }
    }
}

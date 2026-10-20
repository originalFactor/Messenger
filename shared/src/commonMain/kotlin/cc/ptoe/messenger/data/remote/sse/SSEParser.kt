/*
 * Copyright 2026 originalFactor
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

package cc.ptoe.messenger.data.remote.sse

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object SSEParser {

    /**
     * Reads a Ktor [ByteReadChannel] line by line and emits every SSE
     * `data:` payload. The terminal `\[DONE]` sentinel is forwarded so
     * downstream can emit Done instead of mistaking the clean
     * end-of-stream for an error.
     */
    fun parse(channel: ByteReadChannel): Flow<String> = flow {
        while (!channel.isClosedForRead) {
            val line = channel.readLine() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ")
                if (data == "[DONE]") {
                    emit("[DONE]")
                    break
                }
                emit(data)
            }
        }
    }
}

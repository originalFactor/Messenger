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

package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A bounded, live window over a conversation. All database reads run off the UI thread. */
internal class ChatHistoryPager(
    private val repository: MessageRepository,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
    private val pageSize: Int = 30,
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var olderJob: Job? = null
    private var generation = 0L
    private var conversationId: String? = null
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder = _hasOlder.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    fun start(id: String) {
        if (conversationId == id) return
        generation++
        val currentGeneration = generation
        job?.cancel()
        olderJob?.cancel()
        conversationId = id
        _messages.value = emptyList()
        _hasOlder.value = false
        _isLoading.value = true
        job = scope.launch {
            repository.observeChanges(id).collect {
                try {
                    mutex.withLock {
                        val previous = _messages.value
                        val anchor = previous.firstOrNull()?.id
                        val loaded = withContext(Dispatchers.Default) {
                            val current = anchor?.let { repository.getFrom(id, it) }.orEmpty()
                            if (anchor != null && current.isNotEmpty()) {
                                repository.getBefore(id, anchor, 1).isNotEmpty() to current
                            } else initialWindow(id, maxOf(pageSize, previous.size))
                        }
                        if (conversationId != id || generation != currentGeneration) return@withLock
                        _hasOlder.value = loaded.first
                        _messages.value = loaded.second
                        _isLoading.value = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == currentGeneration) {
                        _isLoading.value = false
                        _hasOlder.value = false
                        onError(e)
                    }
                }
            }
        }
    }

    fun loadOlder() {
        val id = conversationId ?: return
        if (_isLoading.value || !_hasOlder.value) return
        _isLoading.value = true
        val currentGeneration = generation
        olderJob = scope.launch {
            try {
                mutex.withLock {
                    if (conversationId != id || generation != currentGeneration) return@withLock
                    val anchor = _messages.value.firstOrNull()?.id ?: return@withLock
                    val result = withContext(Dispatchers.Default) {
                        val raw = repository.getBefore(id, anchor, pageSize + 1)
                        val older = completeTurn(id, raw.takeLast(pageSize), raw.size > pageSize)
                        if (older.second.isEmpty()) older
                        else older.first to repository.getFrom(id, older.second.first().id)
                    }
                    if (conversationId == id && generation == currentGeneration) {
                        _hasOlder.value = result.first
                        if (result.second.isNotEmpty()) _messages.value = result.second
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == currentGeneration) {
                    _hasOlder.value = false
                    onError(e)
                }
            } finally {
                if (generation == currentGeneration) _isLoading.value = false
            }
        }
    }

    private suspend fun initialWindow(id: String, count: Int): Pair<Boolean, List<Message>> {
        val raw = repository.getRecent(id, count + 1)
        return completeTurn(id, raw.takeLast(count), raw.size > count)
    }

    // A tool turn may span a page: include preceding rows through its user
    // message rather than temporarily rendering tool results as orphans.
    private suspend fun completeTurn(id: String, initial: List<Message>, more: Boolean): Pair<Boolean, List<Message>> {
        var rows = initial
        var hasMore = more
        while (hasMore && rows.isNotEmpty() && rows.first().role != MessageRole.USER) {
            val previous = repository.getBefore(id, rows.first().id, pageSize + 1)
            val chunk = previous.takeLast(pageSize)
            hasMore = previous.size > pageSize
            if (chunk.isEmpty()) break
            rows = chunk + rows
        }
        return hasMore to rows
    }
}

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

package cc.ptoe.messenger.data.util

/**
 * Non-suspend mutual exclusion on [lock], for the callbacks a platform
 * transport invokes from its own thread. Kotlin's `synchronized` builtin does
 * not exist on wasmJs, so shared code needs this seam; on the browser the
 * actual is a plain pass-through, which is correct because there is exactly
 * one thread.
 */
expect inline fun <T> synchronizedBlock(lock: Any, block: () -> T): T

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

/** Browser logging: plain stdout, which lands in the DevTools console. */
internal actual fun logI(tag: String, message: String) {
    println("I/$tag: $message")
}

internal actual fun logW(tag: String, message: String, throwable: Throwable?) {
    println("W/$tag: $message${throwable?.let { " (${it.message})" }.orEmpty()}")
}

internal actual fun logE(tag: String, message: String, throwable: Throwable?) {
    println("E/$tag: $message${throwable?.let { " (${it.message})" }.orEmpty()}")
}

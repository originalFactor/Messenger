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

@file:OptIn(ExperimentalWasmJsInterop::class)

package cc.ptoe.messenger.core

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.js.JsString

/**
 * `Promise` → coroutine for the wasm-bindgen boundary.
 *
 * Handlers are attached from the JS side rather than through the stdlib's
 * typed `Promise.then`, because several Rust exports resolve `undefined`
 * (the `()`-returning ones) and a typed `Promise<JsAny>` would try to cast
 * that `null` to a non-null value and throw `ClassCastException`.
 */
private suspend fun awaitJs(promise: JsAny?): JsAny? =
    suspendCancellableCoroutine { continuation ->
        attachHandlers(
            promise,
            onFulfilled = { value -> if (continuation.isActive) continuation.resume(value) },
            onRejected = { error ->
                if (continuation.isActive) continuation.resumeWithException(jsFailure(error))
            }
        )
    }

@JsFun("(promise, onFulfilled, onRejected) => { Promise.resolve(promise).then(onFulfilled, onRejected); }")
private external fun attachHandlers(
    promise: JsAny?,
    onFulfilled: (JsAny?) -> Unit,
    onRejected: (JsAny?) -> Unit
)

@JsFun("(error) => String(error)")
private external fun jsFailureText(error: JsAny?): String

private fun jsFailure(error: JsAny?): Throwable =
    IllegalStateException(jsFailureText(error))

/** Awaits a JSON-string result. */
suspend fun awaitJsJson(promise: JsAny?): String =
    (awaitJs(promise) as? JsString)?.toString()
        ?: throw IllegalStateException("expected a JSON string from the wasm core")

/** Awaits a numeric result. */
suspend fun awaitJsLong(promise: JsAny?): Long =
    jsNumberValue(awaitJs(promise)).toLong()

/** Awaits a side-effect-only call. */
suspend fun awaitJsUnit(promise: JsAny?) {
    awaitJs(promise)
}

@JsFun("(n) => n")
private external fun jsNumberValue(value: JsAny?): Double

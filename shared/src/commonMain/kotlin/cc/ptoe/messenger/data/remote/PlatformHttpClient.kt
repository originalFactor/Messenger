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

package cc.ptoe.messenger.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Platform HTTP engine factory. Android/Desktop both run on the OkHttp
 * engine with the original 10s connect / 30s read / 30s write timeouts
 * (matching the pre-KMP Retrofit configuration); other targets can plug
 * in their own engine (e.g. Darwin on iOS).
 */
internal expect fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

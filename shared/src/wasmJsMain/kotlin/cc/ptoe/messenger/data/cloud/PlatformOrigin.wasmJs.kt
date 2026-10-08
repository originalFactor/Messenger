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

package cc.ptoe.messenger.data.cloud

import kotlinx.browser.window

/**
 * The page's own origin: the web bundle is served from the cloud's origin, so
 * every api/ and v1/ request is same-origin and carries the session cookie
 * without any CORS configuration.
 */
actual fun platformOrigin(): String = window.location.origin

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

/**
 * The web bundle is served from the cloud's own origin, so the empty base
 * makes every api/ and v1/ request relative to the hosting page.
 * (`RustCloudFacade` additionally pins the Rust engine's absolute base to
 * `window.location.origin` — that engine's own fallback is the production
 * host, not same-origin.)
 */
actual val defaultCloudServerUrl: String = ""

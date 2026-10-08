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
 * Default cloud SaaS host used by Android/Desktop. The web build serves the
 * bundle from the cloud's own origin, so it defaults to the empty string
 * (relative, resolved against `window.location.origin`) instead.
 */
const val DEFAULT_CLOUD_SERVER_URL = "https://messenger.ptoe.cc"

/** Fallback cloud base for the current platform ("" = same origin on web). */
expect val defaultCloudServerUrl: String

/**
 * 内置云 AI 服务商的固定 ID。登录后由客户端以用户级 AI API Key 自动
 * 配置，指向云端的 OpenAI 兼容代理（{server}/v1）。它不参与云同步：
 * 每台设备在登录/同步时自行创建或刷新，推送与拉取两侧都按此 ID 过滤，
 * 避免删除/重建在设备间互相复活。
 */
const val BUILTIN_PROVIDER_ID = "builtin-messenger-cloud-ai"
const val BUILTIN_PROVIDER_NAME = "Messenger Cloud AI"

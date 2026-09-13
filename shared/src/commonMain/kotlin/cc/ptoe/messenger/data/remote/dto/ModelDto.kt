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

package cc.ptoe.messenger.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelDto(
    @SerialName("id") val id: String = "",
    @SerialName("object") val `object`: String = "",
    @SerialName("created") val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    /** Messenger 云端代理扩展字段：上下文窗口（tokens），0 = 不限。其他服务商通常不返回。 */
    @SerialName("context_window") val contextWindow: Long? = null
)

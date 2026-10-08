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

package cc.ptoe.messenger.domain.repository

import cc.ptoe.messenger.data.cloud.CloudAvatarResponse
import cc.ptoe.messenger.data.cloud.CloudCardPreview
import cc.ptoe.messenger.data.cloud.CloudLoginOutcome
import cc.ptoe.messenger.data.cloud.CloudMarketAgent
import cc.ptoe.messenger.data.cloud.CloudMarketAgentListResponse
import cc.ptoe.messenger.data.cloud.CloudMarketAgentUpdate
import cc.ptoe.messenger.data.cloud.CloudRedeemResponse
import cc.ptoe.messenger.data.cloud.CloudSyncResult
import cc.ptoe.messenger.data.cloud.CloudUser
import cc.ptoe.messenger.domain.model.Agent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The cloud SaaS surface the UI consumes — login, sync, market, avatars.
 *
 * Two implementations exist, selected per platform in `createLocalStores`:
 * - Android/Desktop: `CloudSyncRepository` (Room-backed, in `jvmSharedMain`).
 * - Web: `RustCloudFacade`, which delegates to the Rust sync engine through
 *   [cc.ptoe.messenger.core.CoreBridge] (Room has no wasmJs artifact).
 *
 * The UI must depend on this interface only, so the same screens compile for
 * every target.
 */
interface CloudFacade {
    val user: Flow<CloudUser?>
    val serverUrl: Flow<String>
    val syncError: StateFlow<String?>

    /** Cookie-aware Ktor client used by Coil to load authenticated avatars. */
    val avatarHttpClient: HttpClient

    suspend fun setServerUrl(url: String)

    suspend fun login(email: String, password: String, serverUrl: String? = null): CloudLoginOutcome
    suspend fun register(email: String, password: String, serverUrl: String? = null): CloudLoginOutcome
    suspend fun refreshUser(): CloudUser
    suspend fun logout()

    suspend fun changePassword(currentPassword: String, newPassword: String)
    suspend fun deleteAccount(currentPassword: String)

    suspend fun previewRedeemCard(code: String): CloudCardPreview
    suspend fun redeemCard(code: String): CloudRedeemResponse

    suspend fun listMarketAgents(query: String, cursor: String? = null): CloudMarketAgentListResponse
    suspend fun marketAgent(id: String): CloudMarketAgent
    suspend fun publishMarketAgent(agentId: String): CloudMarketAgent
    suspend fun pushMarketAgentUpdate(agentId: String): CloudMarketAgent
    suspend fun removeMarketAgent(agentId: String)
    suspend fun importMarketAgent(marketId: String): Agent
    suspend fun checkMarketAgentUpdate(agentId: String): CloudMarketAgentUpdate
    suspend fun applyMarketAgentUpdate(agentId: String, market: CloudMarketAgent): Agent

    suspend fun sync(): CloudSyncResult
    suspend fun upload(): CloudSyncResult
    suspend fun completeLogin(useLocalData: Boolean): CloudSyncResult
    suspend fun downloadAndRestore(): CloudSyncResult

    fun requestLocalSync()
    suspend fun cancelPendingLocalSync()
    fun requestLocalChange(type: String, id: String, deleted: Boolean = false)
    fun requestAgentAvatarChange(previous: Agent?, current: Agent?)

    suspend fun uploadUserAvatar(path: String?): CloudAvatarResponse
    suspend fun uploadAgentAvatar(agentId: String, path: String?): CloudAvatarResponse

    suspend fun ensureBuiltinTitleAgent()
}

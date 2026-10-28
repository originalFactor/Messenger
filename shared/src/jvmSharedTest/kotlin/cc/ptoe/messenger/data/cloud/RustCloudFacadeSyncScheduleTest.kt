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

package cc.ptoe.messenger.data.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.AppPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Rust core keeps the pending-upsert queue, but the 750 ms debounce that
 * actually pushes it lives in Kotlin. The Rust-core port dropped the
 * `requestLocalSync()` call from `requestLocalChange()`, so a local edit was
 * queued and never uploaded; these tests pin the scheduling and the
 * `localChangesEnabled` gate that guards it.
 */
class RustCloudFacadeSyncScheduleTest {

    @Test
    fun restoredSessionEnablesLocalChangeTracking() = runBlocking {
        val core = RecordingCoreBridge(signedIn = true)
        val facade = newFacade(core)

        // The session lives in the core's kv, so a restarted process is
        // already signed in — edits must sync without a fresh login.
        facade.requestLocalChange("conversation", "c1")

        waitFor { core.markedChanges.isNotEmpty() }
        assertEquals(listOf("conversation:c1:false"), core.markedChanges)
    }

    @Test
    fun markingAChangeSchedulesTheDebouncedPush() = runBlocking {
        val core = RecordingCoreBridge(signedIn = true)
        val facade = newFacade(core)

        facade.requestLocalChange("conversation", "c1")

        // Nothing is pushed inside the debounce window…
        delay(200)
        assertTrue(core.pushCount.get() == 0, "push must wait for the debounce")

        // …but the queue is drained once it elapses.
        waitFor { core.pushCount.get() > 0 }
        assertEquals(1, core.pushCount.get())
    }

    @Test
    fun rapidChangesCollapseIntoOnePush() = runBlocking {
        val core = RecordingCoreBridge(signedIn = true)
        val facade = newFacade(core)

        repeat(5) { index ->
            facade.requestLocalChange("conversation", "c$index")
            delay(50)
        }

        waitFor { core.pushCount.get() > 0 }
        delay(300)
        assertEquals(1, core.pushCount.get(), "the debounce collapses a burst into one push")
        assertEquals(5, core.markedChanges.size)
    }

    @Test
    fun signedOutClientsQueueNothing() = runBlocking {
        val core = RecordingCoreBridge(signedIn = false)
        val facade = newFacade(core)

        facade.requestLocalChange("conversation", "c1")
        delay(400)

        assertFalse(core.markedChanges.isNotEmpty(), "a signed-out store must not queue upserts")
        assertEquals(0, core.pushCount.get())
    }

    @Test
    fun refreshCachesTheCloudAvatarOnce() = runBlocking<Unit> {
        val core = RecordingCoreBridge(signedIn = true)
        val cachedFile = java.nio.file.Files.createTempFile("avatar", ".png").toFile()
        core.cacheAvatarResult = cachedFile.absolutePath
        core.refreshUserReturns(cloudUserJson(id = "acct-1", avatarVersion = 7))
        val preferences = newPreferences()
        val facade = newFacade(core, preferences)

        facade.refreshUser()
        assertEquals(
            listOf("user|acct-1|https://example.com/api/avatars/user|7"),
            core.cachedAvatars,
        )
        assertEquals(cachedFile.absolutePath, AppPreferences(preferences).userAvatar.first())

        // Same url + version: the local file is already current, so no download.
        facade.refreshUser()
        assertEquals(1, core.cachedAvatars.size)

        cachedFile.delete()
    }

    @Test
    fun avatarKeyIncludesTheAccount() = runBlocking<Unit> {
        val core = RecordingCoreBridge(signedIn = true)
        core.cacheAvatarResult = java.nio.file.Files.createTempFile("avatar", ".png").toFile().absolutePath
        val preferences = newPreferences()
        val facade = newFacade(core, preferences)

        core.refreshUserReturns(cloudUserJson(id = "acct-1", avatarVersion = 7))
        facade.refreshUser()
        // A second account on the same device gets the SAME proxy URL; the
        // cached file belongs to the first account, so it must be re-fetched.
        core.refreshUserReturns(cloudUserJson(id = "acct-2", avatarVersion = 7))
        facade.refreshUser()

        assertEquals(2, core.cachedAvatars.size, "the sync key must carry the user id")

        java.io.File(core.cacheAvatarResult).delete()
    }

    @Test
    fun removedCloudAvatarClearsTheLocalFile() = runBlocking<Unit> {
        val core = RecordingCoreBridge(signedIn = true)
        val cachedFile = java.nio.file.Files.createTempFile("avatar", ".png").toFile()
        core.cacheAvatarResult = cachedFile.absolutePath
        core.refreshUserReturns(cloudUserJson(id = "acct-1", avatarVersion = 7))
        val preferences = newPreferences()
        val appPreferences = AppPreferences(preferences)
        val facade = newFacade(core, preferences)

        facade.refreshUser()
        assertTrue(cachedFile.exists())

        // The user center removed the avatar: the local copy must go too.
        core.refreshUserReturns(cloudUserJson(id = "acct-1", avatarVersion = null))
        facade.refreshUser()

        assertFalse(cachedFile.exists(), "the cached avatar file must be deleted")
        assertEquals(null, appPreferences.userAvatar.first())
        assertEquals(null, appPreferences.userAvatarSyncKey.first())
    }

    private fun cloudUserJson(id: String, avatarVersion: Long?): String = buildString {
        append("""{"id":"$id","email":"a@b.c","syncVersion":3""")
        if (avatarVersion != null) {
            append(""","avatarUrl":"https://example.com/api/avatars/user"""")
            append(""","avatarVersion":$avatarVersion""")
        }
        append("}")
    }

    private fun newFacade(
        core: CoreBridge,
        preferences: DataStore<Preferences> = newPreferences(),
    ) = RustCloudFacade(
        core = core,
        appPreferences = AppPreferences(preferences),
    )

    private fun newPreferences(): DataStore<Preferences> = InMemoryPreferences()

    private suspend fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) {
                    delay(25)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("condition not reached within ${timeoutMs}ms")
        }
    }

    /** Counts the calls the facade makes through the core boundary. */
    private class RecordingCoreBridge(
        private val signedIn: Boolean,
        private var refreshUserJson: String? = null,
    ) : CoreBridge {
        val markedChanges = CopyOnWriteArrayList<String>()
        val pushCount = java.util.concurrent.atomic.AtomicInteger(0)
        val cachedAvatars = CopyOnWriteArrayList<String>()
        var cacheAvatarResult: String = ""

        fun refreshUserReturns(json: String) {
            refreshUserJson = json
        }

        private val userJson =
            if (signedIn) """{"id":"acct-1","email":"a@b.c","syncVersion":3}""" else null

        override fun subscribe(listener: (kind: String, ids: List<String>) -> Unit) {}

        override fun cloudCurrentUserJson(): String? = userJson
        override fun cloudGetServerUrl(): String = "https://example.com"
        override fun cloudSetServerUrl(url: String) {}
        override fun cloudHasLocalData(): Boolean = false

        override fun cloudMarkChange(kind: String, id: String, deleted: Boolean) {
            markedChanges += "$kind:$id:$deleted"
        }

        override suspend fun cloudPushPending(): String {
            pushCount.incrementAndGet()
            return """{"latestVersion":1,"agents":0,"conversations":1,"providers":0}"""
        }

        // Unused surface.
        override fun listProvidersJson(): String = "[]"
        override fun getProviderJson(id: String): String? = null
        override fun upsertProviderJson(json: String) {}
        override fun deleteProvider(id: String) {}
        override fun listModelsJson(): String = "[]"
        override fun listModelsByProviderJson(providerId: String): String = "[]"
        override fun getModelJson(id: String): String? = null
        override fun upsertModelJson(json: String) {}
        override fun setModelEnabled(id: String, enabled: Boolean) {}
        override fun deleteModel(id: String) {}
        override fun listAgentsJson(): String = "[]"
        override fun getAgentJson(id: String): String? = null
        override fun getDefaultAgentJson(): String? = null
        override fun getTitleAgentJson(): String? = null
        override fun upsertAgentJson(json: String) {}
        override fun deleteAgent(id: String) {}
        override fun listProjectsJson(): String = "[]"
        override fun getProjectJson(id: String): String? = null
        override fun upsertProjectJson(json: String) {}
        override fun deleteProject(id: String) {}
        override fun listConversationsJson(): String = "[]"
        override fun listConversationsByAgentJson(agentId: String): String = "[]"
        override fun listConversationsByProjectJson(projectId: String): String = "[]"
        override fun getConversationJson(id: String): String? = null
        override fun upsertConversationJson(json: String) {}
        override fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long) {}
        override fun deleteConversation(id: String) {}
        override fun sweepEmptyConversations(excludeIdsJson: String): String = "[]"
        override fun listMessagesByConversationJson(conversationId: String): String = "[]"
        override fun listRecentMessagesByConversationJson(conversationId: String, limit: UInt): String =
            listMessagesByConversationJson(conversationId)

        override fun listMessagesBeforeJson(conversationId: String, beforeId: String, limit: UInt): String = "[]"
        override fun listMessagesFromJson(conversationId: String, fromId: String): String = "[]"

        override fun getMessageJson(id: String): String? = null
        override fun upsertMessageJson(json: String) {}
        override fun deleteMessage(id: String) {}
        override fun deleteMessagesByConversation(conversationId: String) {}
        override fun kvGet(key: String): String? = null
        override fun kvSet(key: String, value: String) {}
        override fun kvDelete(key: String) {}

        override suspend fun cloudLogin(email: String, password: String): String = "{}"
        override suspend fun cloudRegister(email: String, password: String): String = "{}"
        override suspend fun cloudLogout() {}
        override suspend fun cloudRefreshUser(): String = refreshUserJson ?: userJson ?: "{}"
        override suspend fun cloudSync(replaceLocal: Boolean): String = "{}"
        override suspend fun cloudPreviewCard(code: String): String = "{}"
        override suspend fun cloudRedeemCard(code: String): String = "{}"
        override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = 0
        override suspend fun discoverProviderModelsJson(
            baseUrl: String,
            apiKey: String,
            apiFormat: String?
        ): String = "[]"
        override suspend fun modelsDevMetadataJson(modelId: String): String? = null
        override suspend fun cloudListMarketAgents(query: String, cursor: String?): String = "{}"
        override suspend fun cloudGetMarketAgent(id: String): String = "{}"
        override suspend fun cloudPublishMarketAgent(agentId: String): String = "{}"
        override suspend fun cloudImportMarketAgent(marketId: String): String = "{}"
        override suspend fun cloudCacheAvatar(
            scope: String,
            accountId: String,
            id: String,
            url: String,
            version: String?,
            destDir: String
        ): String {
            cachedAvatars += "$scope|$accountId|$url|${version.orEmpty()}"
            return cacheAvatarResult
        }
        override suspend fun cloudPushSnapshot(): Long = 0
        override suspend fun cloudSyncWith(
            since: Long?,
            replaceLocal: Boolean,
            expectedServerVersion: Long?
        ): String = "{}"
        override suspend fun cloudReplaceCloudWithLocal(): String = "{}"
        override suspend fun cloudChangePassword(currentPassword: String, newPassword: String) {}
        override suspend fun cloudDeleteAccount(currentPassword: String) {}
        override suspend fun cloudEnsureBuiltinTitleAgent() {}
        override suspend fun cloudUploadUserAvatar(bytes: ByteArray, filename: String, mime: String): String = ""
        override suspend fun cloudDeleteUserAvatar(): String = ""
        override suspend fun cloudUploadAgentAvatar(
            agentId: String,
            bytes: ByteArray,
            filename: String,
            mime: String
        ): String = ""
        override suspend fun cloudDeleteAgentAvatar(agentId: String): String = ""
        override suspend fun cloudPushMarketAgentUpdate(agentId: String): String = "{}"
        override suspend fun cloudRemoveMarketAgent(agentId: String) {}
        override suspend fun cloudCheckMarketAgentUpdate(agentId: String): String = "{}"
        override suspend fun cloudApplyMarketAgentUpdate(agentId: String, marketJson: String): String = "{}"
        override suspend fun cloudImportMarketAgentWithAvatar(marketId: String): String = "{}"
        override suspend fun cloudClearMarketLinksAndBuiltinProvider() {}
        override fun clearLocalDataForReinit() {}
        override fun cancelTurn() {}
        override suspend fun runTurn(
            config: TurnConfigBridge,
            toolExecutor: suspend (String, String) -> Pair<String, Boolean>,
            onEventJson: (String) -> Unit
        ) {}
    }

    /** Minimal preferences sink; the facade keeps all cloud state in the core. */
    private class InMemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences = transform(state.value).also { state.value = it }
    }
}

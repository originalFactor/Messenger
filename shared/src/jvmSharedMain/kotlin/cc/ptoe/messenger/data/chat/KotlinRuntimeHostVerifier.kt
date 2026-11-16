/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.domain.chat.KotlinRuntimeToolGrant
import cc.ptoe.messenger.domain.tool.builtinChatToolsSupported
import cc.ptoe.messenger.domain.tool.temporaryChatWorkspace
import cc.ptoe.messenger.presentation.platform.APP_VERSION_CODE
import cc.ptoe.messenger.presentation.platform.APP_VERSION_NAME
import java.util.concurrent.ConcurrentHashMap

/**
 * A grant is verified outside the database transaction before a turn starts or recovers.
 * Rebinding after a process restart requires another successful refresh. The supplied device ID
 * must identify this installation, not a remote device; null fails closed for owned projects.
 */
class KotlinRuntimeHostVerifier(
    private val currentDeviceId: () -> String?,
    private val runtimeAvailable: () -> Boolean = ::builtinChatToolsSupported,
    private val temporaryWorkspace: suspend (String) -> String? = ::temporaryChatWorkspace,
    private val buildIdentity: String = "$APP_VERSION_NAME:$APP_VERSION_CODE",
) : RuntimeWorkspaceVerifier {
    private val verified = ConcurrentHashMap<String, KotlinRuntimeToolGrant>()

    suspend fun refresh(grant: KotlinRuntimeToolGrant): Boolean {
        verified.remove(grant.conversationId)
        if (!baseChecks(grant)) return false
        if (grant.projectId == null && temporaryWorkspace(grant.conversationId) != grant.workspaceRoot) return false
        verified[grant.conversationId] = grant
        return true
    }

    override suspend fun isAvailable(grant: KotlinRuntimeToolGrant): Boolean =
        verified[grant.conversationId] == grant && baseChecks(grant)

    private fun baseChecks(grant: KotlinRuntimeToolGrant): Boolean {
        if (grant.hostIdentity != buildIdentity || !runtimeAvailable()) return false
        val owner = grant.deviceId
        if (grant.projectId != null && owner.isNullOrBlank()) return false
        if (owner != null && owner != currentDeviceId()) return false
        return true
    }

    fun invalidate(conversationId: String) { verified.remove(conversationId) }

    companion object { val currentBuildIdentity: String get() = "$APP_VERSION_NAME:$APP_VERSION_CODE" }
}

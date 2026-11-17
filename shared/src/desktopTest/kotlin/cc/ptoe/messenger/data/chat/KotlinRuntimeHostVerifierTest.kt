/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.domain.chat.KotlinRuntimeToolGrant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRuntimeHostVerifierTest {
    @Test fun bindsHostDeviceAndTemporaryWorkspaceAndRechecksAvailability() = runBlocking {
        var running = true
        var device = "local"
        val verifier = KotlinRuntimeHostVerifier({ device }, { running }, { "/temporary/$it" }, "build")
        val grant = KotlinRuntimeToolGrant("chat", null, "/temporary/chat", null, "build", listOf("read"))
        assertFalse(verifier.isAvailable(grant))
        assertTrue(verifier.refresh(grant))
        assertTrue(verifier.isAvailable(grant))
        running = false
        assertFalse(verifier.isAvailable(grant))
        running = true
        assertFalse(verifier.refresh(grant.copy(workspaceRoot = "/other")))
        assertFalse(verifier.isAvailable(grant))
        val project = grant.copy(projectId = "project", workspaceRoot = "/project", deviceId = "local")
        assertFalse(verifier.refresh(project.copy(deviceId = null)))
        assertFalse(verifier.refresh(project.copy(deviceId = "")))
        assertTrue(verifier.refresh(project))
        device = "different"
        assertFalse(verifier.isAvailable(project))
        assertFalse(verifier.refresh(project.copy(hostIdentity = "old-build")))
    }
}

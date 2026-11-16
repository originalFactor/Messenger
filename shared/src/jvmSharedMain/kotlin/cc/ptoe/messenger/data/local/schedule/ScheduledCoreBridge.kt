/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import cc.ptoe.messenger.core.CoreBridge

/** Composition point for existing schedule screens; never installed until Kotlin cutover. */
internal class ScheduledCoreBridge(private val delegate: CoreBridge, private val schedules: RoomScheduledTaskBridge) : CoreBridge by delegate {
    override suspend fun scheduledTaskRequest(method: String, path: String, body: String?): String = schedules.request(method, path, body)
}

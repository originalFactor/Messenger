/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Owns each registered request until its caller unregisters it or its socket closes. */
internal class WearChatFrameRegistry {
    private val maxQueuedFrames = 4_096
    private class Entry(val channel: Channel<WearChatFrame>, var socket: Any? = null)
    private val lock = Any()
    private val requests = mutableMapOf<String, Entry>()

    fun register(requestId: String): ReceiveChannel<WearChatFrame> = synchronized(lock) {
        require(requestId.isNotBlank()) { "Chat request ID is required" }
        check(requestId !in requests) { "Chat request ID is already registered" }
        Channel<WearChatFrame>(maxQueuedFrames).also { requests[requestId] = Entry(it) }
    }

    /** Bind before sending so even an immediate reply belongs to the right socket. */
    fun bind(requestId: String, socket: Any): Boolean = synchronized(lock) {
        val entry = requests[requestId] ?: return false
        check(entry.socket == null) { "Chat request is already bound" }
        entry.socket = socket
        true
    }

    fun deliver(socket: Any, frame: WearChatFrame) = synchronized(lock) {
        val entry = requests[frame.requestId]?.takeIf { it.socket === socket }
        if (entry != null && entry.channel.trySend(frame).isFailure) {
            requests.remove(frame.requestId)
            entry.channel.close(IOException("Chat frame buffer exhausted; synchronize with the phone"))
        }
        Unit
    }

    fun disconnected(socket: Any, reason: String) = synchronized(lock) {
        val iterator = requests.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.socket === socket) {
                iterator.remove()
                entry.channel.close(IOException(reason))
            }
        }
    }

    fun unregister(requestId: String) = synchronized(lock) {
        requests.remove(requestId)?.channel?.close()
        Unit
    }
}

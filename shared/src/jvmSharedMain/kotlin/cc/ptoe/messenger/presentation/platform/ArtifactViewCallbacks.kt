/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

/** Compose keys compare equal values; host capabilities instead require reference identity. */
internal class ArtifactBindingIdentity(private val identity: Any) {
    override fun equals(other: Any?): Boolean = other is ArtifactBindingIdentity && identity === other.identity
    override fun hashCode(): Int = System.identityHashCode(identity)
}

/** One native sandbox owns exactly one callback authority and revokes it once on teardown. */
internal class ArtifactViewCallbacks(
    private val handler: (String, (String) -> Unit) -> Unit,
    private val onDispose: () -> Unit,
) {
    private val lock = Any()
    private var closed = false

    val active: Boolean get() = synchronized(lock) { !closed }

    fun dispatch(raw: String, reply: (String) -> Unit) {
        if (synchronized(lock) { closed }) return
        // The handler may acquire a binding lock while a reply holds that binding lock and
        // enters this callback. Never invoke the handler while holding this monitor.
        handler(raw) { value -> synchronized(lock) { if (!closed) reply(value) } }
    }

    fun close() {
        val shouldDispose = synchronized(lock) {
            if (closed) false else { closed = true; true }
        }
        if (shouldDispose) onDispose()
    }
}

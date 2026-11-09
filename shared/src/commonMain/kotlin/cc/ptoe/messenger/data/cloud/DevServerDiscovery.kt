package cc.ptoe.messenger.data.cloud

/** Explicit, credential-free local discovery; never changes the saved server/session. */
expect suspend fun discoverDevServers(): List<String>

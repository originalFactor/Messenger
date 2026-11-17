/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.wear

/** The backend host must drain Wear entry points before closing its database owner. */
object MobileWearServiceOwner {
    internal val requests = WearRequestRegistry()

    /** Permanently closes Wear admission for this process and waits for all request cleanup. */
    suspend fun stopAndJoin() = requests.stopAndJoin()
}

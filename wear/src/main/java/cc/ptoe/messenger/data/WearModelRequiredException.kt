/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

/** The request is known not to have been sent or admitted by the phone. */
open class WearNotAdmittedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** The phone or cached agent rejected the request before admitting a chat turn. */
class WearModelRequiredException(message: String) : WearNotAdmittedException(message)

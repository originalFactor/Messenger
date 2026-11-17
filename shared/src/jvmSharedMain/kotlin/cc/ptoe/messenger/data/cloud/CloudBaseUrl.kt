/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import java.net.URI
import java.util.Locale

/** Exact server base, including its path; credentials are never shared across base paths. */
internal fun canonicalCloudBase(raw: String): String {
    val uri = URI(raw)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath == uri.normalize().rawPath &&
        !raw.contains('\\')) { "Invalid cloud server base URL" }
    val rawHost = uri.host.lowercase(Locale.ROOT)
    val host = if (':' in rawHost && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
    val port = uri.port
    require(port in -1..65535) { "Invalid cloud server port" }
    val authority = if (port == -1 || scheme == "http" && port == 80 || scheme == "https" && port == 443) host else "$host:$port"
    return "$scheme://$authority" + uri.rawPath.trimEnd('/')
}

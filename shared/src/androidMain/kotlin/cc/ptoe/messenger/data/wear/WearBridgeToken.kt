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

package cc.ptoe.messenger.data.wear

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Shared secret for the phone→watch WebSocket bridge.
 *
 * The server listens on all interfaces (the watch reaches it over the
 * Bluetooth PAN, so loopback is not enough) and every request it accepts
 * reads or writes the user's chat data. A device on the same Wi-Fi can
 * therefore reach the port, so the token is the actual access control: it is
 * advertised only in the mDNS TXT record and must match on the WebSocket
 * handshake. It persists across restarts (the watch caches the endpoint) and
 * is replaced only when the app's data is cleared.
 */
internal object WearBridgeToken {

    private const val PREFS = "wear_bridge"
    private const val KEY = "token"

    /** NSD TXT attribute name carrying the token. */
    const val ATTRIBUTE = "token"

    /** Query parameter the watch appends to the WebSocket URL. */
    const val QUERY_PARAM = "token"

    /** Current token, generating (and persisting) one on first use. */
    fun current(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        prefs.edit().putString(KEY, token).apply()
        return token
    }
}

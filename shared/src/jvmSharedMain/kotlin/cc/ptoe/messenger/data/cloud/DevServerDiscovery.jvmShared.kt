package cc.ptoe.messenger.data.cloud

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun discoveryPort(payload: String): Int? = runCatching {
    val fields = Json.parseToJsonElement(payload).jsonObject
    if (fields["service"]?.jsonPrimitive?.content != "messenger-cloud" ||
        fields["version"]?.jsonPrimitive?.intOrNull != 1) return null
    fields["port"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..65535 }
}.getOrNull()

actual suspend fun discoverDevServers(): List<String> = withContext(Dispatchers.IO) {
    val candidates = linkedSetOf<String>()
    // Emulator networking does not forward LAN broadcasts. Probe its host gateway too.
    for (host in listOf("127.0.0.1", "10.0.2.2")) {
        for (port in 3000..3005) candidates += "http://$host:$port"
    }
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 250
        val destinations = linkedSetOf(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("10.0.2.2"),
            InetAddress.getByName("255.255.255.255")
        )
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.forEach { network ->
            network.interfaceAddresses.mapNotNullTo(destinations) { it.broadcast }
        }
        val query = "messenger-cloud-discover-v1".toByteArray(Charsets.UTF_8)
        destinations.forEach { address ->
            // One disconnected interface must not prevent discovery through the others.
            runCatching { socket.send(DatagramPacket(query, query.size, address, 53319)) }
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline && candidates.size < 64) {
            currentCoroutineContext().ensureActive()
            val packet = DatagramPacket(ByteArray(1024), 1024)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (packet.address !is Inet4Address || !(packet.address.isLoopbackAddress || packet.address.isSiteLocalAddress)) continue
            val port = discoveryPort(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            candidates += "http://${packet.address.hostAddress}:$port"
        }
    }
    coroutineScope {
        candidates.map { base -> async {
            currentCoroutineContext().ensureActive()
            val connection = URI("$base/api/dev/discovery").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2000
                connection.readTimeout = 5000 // Allow Next's first development route compilation.
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200) return@async null
                val body = connection.inputStream.use { input ->
                    val bytes = ByteArray(2049)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count < 0) break
                        size += count
                    }
                    bytes.copyOf(size)
                }
                if (body.size > 2048) return@async null
                val fields = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                base.takeIf {
                    fields["service"]?.jsonPrimitive?.content == "messenger-cloud" &&
                        fields["version"]?.jsonPrimitive?.intOrNull == 1 &&
                        fields["development"]?.jsonPrimitive?.booleanOrNull == true
                }
            } catch (_: java.io.IOException) {
                null // Unoccupied ports and unreachable hosts are normal during discovery.
            } catch (_: IllegalArgumentException) {
                null // An unrelated HTTP service is not a Messenger dev server.
            } finally {
                connection.disconnect()
            }
        } }.awaitAll().filterNotNull().sorted()
    }
}

/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AcpTakeoverWorkerTest {
    internal class Transport(private val script: Path) : AcpLineTransport {
        lateinit var process: Process
        lateinit var input: BufferedWriter
        lateinit var output: BufferedReader
        var suppliedEnvironment: Map<String, String> = emptyMap()
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
            suppliedEnvironment = configuration.environment
            val builder = ProcessBuilder("python3", script.toString()).directory(script.parent.toFile())
            builder.environment().putAll(configuration.environment)
            process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start()
            input = process.outputStream.bufferedWriter(); output = process.inputStream.bufferedReader()
        }
        override suspend fun readLine() = withContext(Dispatchers.IO) { output.readLine() }
        override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) { input.write(line); input.newLine(); input.flush() }
        override suspend fun stopAndAwait() = withContext(Dispatchers.IO) {
            if (::process.isInitialized) { process.destroyForcibly(); check(process.waitFor(5, TimeUnit.SECONDS)) }
        }
    }

    private val backend = object : AcpGatewayBackend {
        override suspend fun liveTools() = emptyList<AcpGatewayTool>()
        override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unexpected", isError = true)
    }
    internal val script = """
import json, os, sys, urllib.request, urllib.error
def send(**fields):
    print(json.dumps(dict(jsonrpc="2.0", **fields)), flush=True)
for line in sys.stdin:
    packet = json.loads(line)
    method, request_id = packet.get("method"), packet.get("id")
    if method == "initialize":
        send(id=request_id, result={"protocolVersion":1,"agentCapabilities":{"loadSession":True}})
    elif method == "session/load":
        send(id=request_id, result={})
    elif method == "session/prompt":
        url = os.environ["ANTHROPIC_BASE_URL"] + "/v1/messages"
        blocked = json.dumps({"model":"messenger/hidden","max_tokens":20,"messages":[]}).encode()
        try:
            urllib.request.urlopen(urllib.request.Request(url, data=blocked, headers={"x-api-key":os.environ["ANTHROPIC_AUTH_TOKEN"],"Content-Type":"application/json"}), timeout=5)
            raise RuntimeError("unavailable model was exposed")
        except urllib.error.HTTPError as failure:
            assert failure.code == 400
        body = json.dumps({"model":"messenger/model","max_tokens":20,"messages":[{"role":"user","content":"hi"}]}).encode()
        req = urllib.request.Request(url, data=body, headers={"x-api-key":os.environ["ANTHROPIC_AUTH_TOKEN"],"Content-Type":"application/json"})
        with urllib.request.urlopen(req, timeout=5) as response:
            result = json.load(response)
        send(method="session/update", params={"sessionId":packet["params"]["sessionId"],"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":result["content"][0]["text"]}}})
        send(id=request_id, result={"stopReason":"end_turn"})
""".trimIndent()

    @Test fun realStdioWorkerUsesTurnBoundRelayAndNeverReceivesProviderCredentials(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-takeover-worker")
        val path = dir.resolve("agent.py"); Files.writeString(path, script)
        val count = AtomicInteger()
        var authorization: String? = null
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/v1/messages") { exchange ->
            count.incrementAndGet(); authorization = exchange.requestHeaders.getFirst("x-api-key")
            val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            check(request["model"]!!.jsonPrimitive.content == "upstream-model")
            val bytes = """{"id":"response","content":[{"type":"text","text":"relayed"}]}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        upstream.start()
        val model = AcpRelayModel("model", "Model", "provider", 1, true, "upstream-model",
            "http://127.0.0.1:${upstream.address.port}/v1", "provider-secret", AcpRelayFormat.MESSAGES)
        val hidden = AcpRelayModel("hidden", "Hidden", "provider", 1, true, "upstream-model",
            "http://127.0.0.1:${upstream.address.port}/v1", "provider-secret", AcpRelayFormat.MESSAGES)
        val transport = Transport(path)
        val text = CompletableDeferred<String>()
        val raw = AcpStdioWorker(transport, AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {
            override suspend fun update(turnId: String?, update: JsonObject) {
                ((update["content"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let { text.complete(it) }
            }
        })
        val worker = AcpTakeoverWorker(raw, AcpRelayCatalog { if (it == "model") model else if (it == "hidden") hidden else null },
            listOf(AcpTakeoverModel("model", "Model", "messages", true), AcpTakeoverModel("hidden", "Hidden", "unsupported", true)), "model")
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "claude-code", "project", dir.toString(), "session", false)
        val config = AcpWorkerConfiguration("python3", emptyList(), mapOf("CUSTOM" to "preserved"), JsonArray(emptyList()), false, emptySet())
        try {
            worker.start(binding, config, gateway)
            assertEquals("preserved", transport.suppliedEnvironment["CUSTOM"])
            assertFalse(transport.suppliedEnvironment.values.any { it.contains("provider-secret") })
            val base = checkNotNull(transport.suppliedEnvironment["ANTHROPIC_BASE_URL"])
            val token = checkNotNull(transport.suppliedEnvironment["ANTHROPIC_AUTH_TOKEN"])
            val beforeTurn = withContext(Dispatchers.IO) {
                val connection = URL("$base/v1/messages").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.setRequestProperty("x-api-key", token)
                val body = """{"model":"messenger/model","messages":[]}""".encodeToByteArray()
                connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) }
                try { connection.responseCode } finally { connection.disconnect() }
            }
            assertEquals(409, beforeTurn)
            val result = withTimeout(10_000) { worker.prompt("turn", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "hello") })), AcpPermissionBroker()) }
            assertEquals("Finished", result["type"]!!.jsonPrimitive.content)
            assertEquals("relayed", withTimeout(5_000) { text.await() })
            assertEquals("provider-secret", authorization)
            assertEquals(1, count.get())
            assertFails { withContext(Dispatchers.IO) { URL("$base/v1/models").openConnection().getInputStream().close() } }
            assertFails { worker.prompt("another", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "again") })), AcpPermissionBroker()) }
        } finally { worker.stopAndAwait(); gateway.close(); upstream.stop(0); dir.toFile().deleteRecursively() }
    }
}

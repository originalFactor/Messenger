/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.core.AcpProcessChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Platform callback must prove process and owned side effects have stopped; otherwise it throws. */
internal fun interface AcpStoppedProof { suspend fun requireStopped(binding: AcpWorkerBinding) }
internal fun interface AcpChannelHost { suspend fun run(channel: AcpProcessChannel) }

/** Adapter to the existing Runtime ACP stdio channel, with bounded in-memory buffering. */
internal class AcpChannelTransport(private val host: AcpChannelHost, private val proof: AcpStoppedProof) : AcpLineTransport {
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val output = Channel<String>(32)
    private val input = Channel<String>(32)
    private var binding: AcpWorkerBinding? = null
    private var hostJob: Job? = null

    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
        check(hostJob == null && this.binding == null) { "ACP process is already started" }
        this.binding = binding
        val process = object : AcpProcessChannel {
            override val command = configuration.command
            override val arguments = configuration.arguments.toList()
            override val envJson = Json.encodeToString(configuration.environment)
            override val cwd = binding.workspace
            override suspend fun output(line: String) {
                require(line.encodeToByteArray().size <= 192_000) { "ACP stdout line exceeds limit" }
                output.send(line)
            }
            override suspend fun readInput(write: suspend (String) -> Unit) {
                for (line in input) write(line)
            }
        }
        hostJob = scope.launch {
            try { host.run(process); output.close() }
            catch (cancelled: CancellationException) { output.close(); throw cancelled }
            catch (_: Exception) { output.close(IllegalStateException("ACP process host failed")) }
        }
    }
    override suspend fun readLine(): String? {
        val result = output.receiveCatching()
        result.exceptionOrNull()?.let { throw it }
        return result.getOrNull()
    }
    override suspend fun sendLine(line: String) {
        require(line.encodeToByteArray().size <= 192_000) { "ACP stdin line exceeds limit" }
        input.send(line)
    }
    override suspend fun stopAndAwait() = withContext(NonCancellable) {
        input.close()
        hostJob?.cancelAndJoin()
        output.cancel()
        owner.cancelAndJoin()
        proof.requireStopped(checkNotNull(binding) { "ACP process was not started" })
    }
}

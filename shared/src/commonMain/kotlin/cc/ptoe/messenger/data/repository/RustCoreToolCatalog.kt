package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Settings-only descriptor. Rust declares and executes these capabilities. */
@Serializable
data class CoreToolDescriptor(
    override val name: String,
    override val description: String,
    override val parametersJson: String,
    @SerialName("enabled") val capabilityEnabled: Boolean = false,
) : ChatTool {
    override suspend fun execute(argumentsJson: String) = ToolExecutionResult(
        output = "This tool is owned by Messenger Core and cannot execute through the platform tool host.",
        isError = true,
    )
}

data class CoreToolCatalogState(
    val tools: List<CoreToolDescriptor> = emptyList(),
    val ready: Boolean = false,
    val error: String? = null,
)

/** A failed refresh preserves the last inventory and never blocks app startup. */
class RustCoreToolCatalog(private val load: suspend () -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CoreToolCatalogState())
    val state = mutableState.asStateFlow()

    @Serializable
    private data class Catalog(val tools: List<CoreToolDescriptor>)

    suspend fun refresh() = mutex.withLock {
        try {
            val tools = json.decodeFromString<Catalog>(load()).tools.distinctBy { it.name }
            mutableState.value = CoreToolCatalogState(tools = tools, ready = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = error.message ?: error.toString())
        }
    }
}

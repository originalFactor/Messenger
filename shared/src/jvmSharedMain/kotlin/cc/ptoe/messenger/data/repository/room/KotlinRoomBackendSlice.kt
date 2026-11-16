/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.KotlinControlSettingsBridge
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.cloud.RoomCloudFacade
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import cc.ptoe.messenger.data.cloud.defaultCloudServerUrl
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.acp.AcpGatewayBackend
import cc.ptoe.messenger.data.local.acp.AcpGatewayTool
import cc.ptoe.messenger.data.local.acp.AcpSkillsGatewayBackend
import cc.ptoe.messenger.data.local.acp.AcpStdioWorker
import cc.ptoe.messenger.data.local.acp.KotlinAcpBackgroundBridge
import cc.ptoe.messenger.data.local.acp.RoomAcpBackgroundOwner
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.artifact.ArtifactExecutionService
import cc.ptoe.messenger.data.local.artifact.ArtifactSandboxHost
import cc.ptoe.messenger.data.local.artifact.RoomArtifactUiHost
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.LearningBundleService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.image.ImageGenerationService
import cc.ptoe.messenger.data.local.image.ImagesHttpClient
import cc.ptoe.messenger.data.local.image.RoomImageJobOwner
import cc.ptoe.messenger.data.local.image.SafeImageDownloader
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.skill.KotlinSkillBridge
import cc.ptoe.messenger.data.local.skill.RemoteSkillImport
import cc.ptoe.messenger.data.local.skill.RoomSkillStore
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTaskBridge
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.ScheduledCoreBridge
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.data.repository.RoomLearningRepository
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.di.LocalStores
import cc.ptoe.messenger.domain.artifact.ArtifactUiHost
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath

/**
 * Partial, unconnected Room-only backend. The enclosing owner must exclude Rust and other Room
 * owners, stop all UI/other database users, and retain Room until [shutdown] has completed.
 * This is not an AppBackend: Runtime tools, ACP Core tools and several media routes remain unavailable.
 * Only opted-in learning and artifact Core tools can execute; the optional scheduler and
 * trusted-UI image jobs require explicit previous-worker stop proofs. Optional ACP requires a
 * platform owner and a configuration fence shared by all project, settings and MCP writers.
 * The optional artifact host requires trusted device-user consent and a native sandbox owner.
 */
internal class KotlinRoomBackendSlice private constructor(
    val stores: LocalStores,
    val bridge: CoreBridge,
    val providers: RoomProviderRepository,
    val models: RoomModelRepository,
    val agents: RoomAgentRepository,
    val conversations: RoomConversationRepository,
    val projects: RoomProjectRepository,
    val messages: RoomMessageRepository,
    val currentAgent: RoomCurrentAgentRepository,
    val learning: RoomLearningRepository,
    val assetGate: RoomAssetGate,
    val scheduler: IsolatedRoomScheduler?,
    val imageOwner: RoomImageJobOwner?,
    val acpOwner: RoomAcpBackgroundOwner?,
    val artifactUiHost: ArtifactUiHost?,
) {
    companion object {
        /** Call only inside a managed, exclusive Room owner after previous workers have stopped. */
        suspend fun attach(
            db: LocalDatabase,
            shutdown: RoomManagedShutdown,
            appPreferences: AppPreferences,
            themePreferences: ThemePreferences,
            assetRoot: Path,
            avatarRoot: Path,
            chatImageRoot: Path,
            defaultServer: String = defaultCloudServerUrl,
            catalogUrl: String = "https://models.dev/models.json",
            now: () -> Long = ::currentTimeMillis,
            schedulerRecovery: ScheduledRecoveryBarrier? = null,
            imageJobs: IsolatedImageJobsConfig? = null,
            acpConfig: IsolatedAcpConfig? = null,
            artifactUi: IsolatedArtifactUiConfig? = null,
            remoteSkills: RemoteSkillImport? = null,
        ): KotlinRoomBackendSlice {
            check(db.rows().defaultAgents().size == 1) { "Exactly one default Agent is required" }
            val gate = RoomAssetGate()
            withContext(NonCancellable) { shutdown.registerStop { gate.shutdown() } }
            val assets = CoreAssetService(assetRoot, db.assetMetadata(), gate)
            val packages = LearningBundleService(assetRoot, gate, db.assetMetadata(), db.learningBundle())
            val learningOwnership = LearningOwnership(db, assets)
            val learning = RoomLearningRepository(db.learningReview(), learningOwnership, assets, packages, now)
            val artifacts = RoomArtifactService(db, assets)
            val artifactHost = artifactUi?.let { config ->
                RoomArtifactUiHost.attach(artifacts, ArtifactSandboxHost(ArtifactExecutionService(artifacts, assets), assets),
                    config.consent, shutdown)
            }

            val transport = RoomCloudHttpClient()
            try { withContext(NonCancellable) { shutdown.registerStop { transport.close() } } }
            catch (failure: Throwable) {
                try { transport.close() } catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
                throw failure
            }
            val cloud = RoomCloudFacade.open(db, appPreferences, transport, RoomAvatarCache(avatarRoot.toString().toPath()),
                CloudImageRehydrator(chatImageRoot), defaultServer, onCreated = { facade ->
                    withContext(NonCancellable) { shutdown.registerStop { facade.shutdown() } }
                })
            val composition = KotlinRoomBridgeComposition.attach(db, shutdown, catalogUrl = catalogUrl,
                syncBuiltinModels = cloud::syncBuiltinModels)
            val skillStore = RoomSkillStore(db)
            val acpOwner = acpConfig?.let { config ->
                val unsupported = object : AcpGatewayBackend {
                    override suspend fun liveTools(): List<AcpGatewayTool> = emptyList()
                    override suspend fun execute(turnId: String, name: String, arguments: JsonObject) =
                        ToolExecutionResult("ACP tool is unavailable", isError = true)
                }
                RoomAcpBackgroundOwner.attach(db, config.databasePath, shutdown, config.previousOwner,
                    config.configurationFence, config.sessions, config.mcpCatalog, AcpSkillsGatewayBackend(skillStore, unsupported),
                    { skillStore.discovery(permitted = true) != null }, { binding, events ->
                        AcpStdioWorker(config.transport(binding), config.endpoints, { skillStore.discovery(permitted = true) }, events)
                    }, now)
            }
            val acp = RoomAcpConversationStore(db, acpOwner?.mutationGate ?: object : AcpSessionMutationGate {
                override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T =
                    error("ACP session mutations require the Kotlin ACP host")
            })
            val providers = RoomProviderRepository(db) { _, _ -> cloud.requestLocalSync() }
            val models = RoomModelRepository(db) { _, _ -> cloud.requestLocalSync() }
            val agents = RoomAgentRepository(db) { previous, current ->
                cloud.requestLocalSync()
                cloud.requestAgentAvatarChange(previous, current)
            }
            val conversations = RoomConversationRepository(db, acp) { _, _ -> cloud.requestLocalSync() }
            val projects = RoomProjectRepository(db) { _, _ -> cloud.requestLocalSync() }
            val messages = RoomMessageRepository(db) { cloud.requestLocalSync() }
            val selected = RoomCurrentAgentRepository(db, agents)

            val skills = KotlinSkillBridge(composition.bridge, skillStore, remote = remoteSkills)
            val controls = KotlinControlSettingsBridge(skills, db)
            val acpBridge = if (acpOwner != null) KotlinAcpBackgroundBridge(controls, acpOwner, checkNotNull(acpConfig).newSession) else controls
            var modelImageTool: IsolatedModelImageTool? = null
            var sharedImageService: ImageGenerationService? = null
            val imageOwner = imageJobs?.let { config ->
                val imageTransport = ImagesHttpClient()
                try { withContext(NonCancellable) { shutdown.registerStop { imageTransport.close() } } }
                catch (failure: Throwable) {
                    try { imageTransport.close() } catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
                    throw failure
                }
                val service = ImageGenerationService(db, assets, imageTransport, SafeImageDownloader(),
                    config.storeDirectory, config.authorization, now)
                val owner = RoomImageJobOwner.open(db, assets, service, shutdown, config.previousWorkersStopped, now,
                    onRegistered = config.recovery::register)
                sharedImageService = service
                modelImageTool = config.modelAuthorization?.let { IsolatedModelImageTool(service, it) }
                owner
            }
            val media = KotlinRoomMediaBridge(acpBridge, db, assets, artifacts, imageOwner = imageOwner, now = now)
            val scheduledTasks = schedulerRecovery?.let { RoomScheduledTasks(db) }
            val scheduledBridge = scheduledTasks?.let { ScheduledCoreBridge(media, RoomScheduledTaskBridge(it)) } ?: media
            val processJob = SupervisorJob()
            val processScope = CoroutineScope(processJob + Dispatchers.IO)
            val rawBridge = IsolatedCoreTurnBridge(scheduledBridge, db, learningOwnership, artifacts, cloud.providerGate, processScope, now, modelImageTool)
            val admissions = PartialChatCalls()
            val bridge = PartialChatBridge(rawBridge, processJob, scheduledTasks, imageOwner, sharedImageService, acpOwner, admissions)
            try { withContext(NonCancellable) { shutdown.registerStop { bridge.shutdown() } } }
            catch (failure: Throwable) {
                try { bridge.shutdown() } catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
                throw failure
            }
            val scheduler = schedulerRecovery?.let {
                IsolatedRoomScheduler(db, checkNotNull(scheduledTasks), learningOwnership, artifacts, cloud.providerGate, processScope, it, now, admissions)
                    .also { scheduler -> scheduler.start() }
            }
            return KotlinRoomBackendSlice(LocalStores(cloud, appPreferences, themePreferences), bridge,
                providers, models, agents, conversations, projects, messages, selected, learning, gate, scheduler, imageOwner, acpOwner,
                artifactHost)
        }
    }
}

/** Tracks bridge calls separately from workers so shutdown cannot race turn admission. */
private class PartialChatBridge(
    private val delegate: CoreBridge,
    private val processJob: Job,
    private val scheduledTasks: RoomScheduledTasks?,
    private val imageOwner: RoomImageJobOwner?,
    private val imageService: ImageGenerationService?,
    private val acpOwner: RoomAcpBackgroundOwner?,
    private val calls: PartialChatCalls,
) : CoreBridge by delegate {
    private suspend fun <T> admitted(block: suspend () -> T): T = calls.run(block)
    override suspend fun startBackgroundTurn(config: TurnConfigBridge) = admitted { delegate.startBackgroundTurn(config) }
    override suspend fun backgroundTurnActive(conversationId: String) = admitted { delegate.backgroundTurnActive(conversationId) }
    override suspend fun observeBackgroundTurn(conversationId: String, onEventJson: (String) -> Unit) =
        admitted { delegate.observeBackgroundTurn(conversationId, onEventJson) }
    override suspend fun cancelBackgroundTurn(conversationId: String) = admitted {
        if (scheduledTasks?.cancelByConversation(conversationId) != true) delegate.cancelBackgroundTurn(conversationId)
    }
    override suspend fun respondToolApproval(callId: String, approved: Boolean) = admitted { delegate.respondToolApproval(callId, approved) }
    override suspend fun toolApprovalSettings(method: String, body: String?) = admitted { delegate.toolApprovalSettings(method, body) }
    override suspend fun hasActiveTasks() = admitted {
        delegate.hasActiveTasks() || scheduledTasks?.hasActiveRuns() == true || imageOwner?.hasActiveJobs() == true || acpOwner?.hasActiveTasks() == true
    }
    override suspend fun learningRequest(method: String, path: String, body: String?) =
        admitted { delegate.learningRequest(method, path, body) }
    override suspend fun learningAsset(id: String) = admitted { delegate.learningAsset(id) }
    override suspend fun listSkills() = admitted { delegate.listSkills() }
    override suspend fun getSkill(name: String) = admitted { delegate.getSkill(name) }
    override suspend fun validateSkill(filesJson: String) = admitted { delegate.validateSkill(filesJson) }
    override suspend fun saveSkill(name: String, packageJson: String) = admitted { delegate.saveSkill(name, packageJson) }
    override suspend fun deleteSkill(name: String) = admitted { delegate.deleteSkill(name) }
    override suspend fun discoverRemoteSkills(requestJson: String) = admitted { delegate.discoverRemoteSkills(requestJson) }
    override suspend fun previewRemoteSkill(requestJson: String) = admitted { delegate.previewRemoteSkill(requestJson) }
    override suspend fun scheduledTaskRequest(method: String, path: String, body: String?) =
        admitted { delegate.scheduledTaskRequest(method, path, body) }
    override suspend fun runTurn(config: TurnConfigBridge, toolExecutor: suspend (String, String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit) = admitted { delegate.runTurn(config, toolExecutor, onEventJson) }
    override fun cancelTurn() { if (!calls.isClosing) delegate.cancelTurn() }

    suspend fun shutdown() = withContext(NonCancellable) {
        // Tool calls run under NonCancellable; cancel the shared image service before joining chat.
        imageService?.stopAndJoin()
        calls.stopAndJoin()
        processJob.cancelAndJoin()
    }
}

/** Keep admitted scopes registered until their children have actually completed. */
internal class PartialChatCalls {
    private val closing = AtomicBoolean(false)
    private val mutex = Mutex()
    private val calls = ConcurrentHashMap.newKeySet<Job>()
    val isClosing: Boolean get() = closing.get()

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        mutex.withLock {
            check(!closing.get()) { "Chat owner is shutting down" }
            calls.add(job)
            job.invokeOnCompletion { calls.remove(job) }
        }
        block()
    }

    suspend fun stopAndJoin() = withContext(NonCancellable) {
        closing.set(true)
        val active = mutex.withLock { calls.toList().also { jobs -> jobs.forEach { it.cancel() } } }
        active.joinAll()
    }
}

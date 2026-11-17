/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.requirePrivateAssetRoot
import cc.ptoe.messenger.data.local.asset.syncAssetRoot
import cc.ptoe.messenger.data.local.asset.validateOwnedImage
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Trusted caller must enforce effective Agent tool policy and any separate tool capability. */
internal fun interface ImageGenerationAuthorization { suspend fun requireAllowed(request: ImageRequest) }

/** Unconnected paid-call service. No retry, fallback provider, or resubmission exists. */
internal class ImageGenerationService(
    private val db: LocalDatabase, private val assets: CoreAssetService, private val transport: ImagesHttpClient,
    private val downloader: SafeImageDownloader, private val storeDirectory: Path,
    private val authorization: ImageGenerationAuthorization, private val now: () -> Long,
    private val syncStore: (Path) -> Unit = ::syncAssetRoot,
) {
    private val active = ConcurrentHashMap<String, CompletableJob>()
    private val reservations = ImageReservationJournal(db.imageJobs())
    private val recoveryRequired = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val admissionLock = Mutex()
    private val admittedCalls = ConcurrentHashMap.newKeySet<Job>()

    internal fun checkHealthy() {
        check(!recoveryRequired.get()) { "Image job cleanup failed; recovery is required before closing Room" }
    }

    /** The owner calls this only after verified stop and durable status recovery. */
    internal fun markRecovered() { recoveryRequired.set(false) }

    /** Prevent new UI/model requests, cancel admitted work and wait for all cleanup. */
    internal suspend fun stopAndJoin() = withContext(NonCancellable) {
        stopping.set(true)
        val jobs = admissionLock.withLock { admittedCalls.toList().also { active -> active.forEach { it.cancel() } } }
        jobs.joinAll()
    }

    suspend fun run(request: ImageRequest, onAdmitted: suspend (ImageJob) -> Unit = {},
        authorizeAdmission: suspend () -> Unit = {}): ImageJob =
        runAuthorized(request, authorization, onAdmitted, authorizeAdmission)

    /** Model-originated requests use their own explicit authorization but the same active jobs. */
    internal suspend fun runFromTool(request: ImageRequest, toolAuthorization: ImageGenerationAuthorization,
        authorizeAdmission: suspend () -> Unit): ImageJob =
        runAuthorized(request, toolAuthorization, {}, authorizeAdmission)

    private suspend fun runAuthorized(request: ImageRequest, selectedAuthorization: ImageGenerationAuthorization,
        onAdmitted: suspend (ImageJob) -> Unit, authorizeAdmission: suspend () -> Unit): ImageJob = coroutineScope {
        val call = checkNotNull(currentCoroutineContext()[Job])
        admissionLock.withLock {
            check(!stopping.get()) { "Image generation is shutting down" }
            admittedCalls.add(call)
            call.invokeOnCompletion { admittedCalls.remove(call) }
        }
        runAuthorizedBody(request, selectedAuthorization, onAdmitted, authorizeAdmission)
    }

    private suspend fun runAuthorizedBody(request: ImageRequest, selectedAuthorization: ImageGenerationAuthorization,
        onAdmitted: suspend (ImageJob) -> Unit, authorizeAdmission: suspend () -> Unit): ImageJob {
        checkHealthy()
        selectedAuthorization.requireAllowed(request)
        currentCoroutineContext().ensureActive()
        var reservation: Path? = null
        var reservationJournal: ImageReservationRecord? = null
        var committedId: String? = null
        val admission = try { withContext(NonCancellable) { assets.withExclusive {
            requireDurableDirectory(storeDirectory)
            assets.requireDurableStorageWithGateHeld()
            db.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA database_list") { statement ->
                    check(statement.step() && statement.getText(1) == "main") { "Main database unavailable" }
                    val databasePath = statement.getText(2)
                    check(databasePath.isNotEmpty()) { "Image jobs require a persistent database" }
                    val databaseFile = Paths.get(databasePath).toAbsolutePath().normalize()
                    check(Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS) &&
                        Files.isSameFile(databaseFile.parent, storeDirectory)) { "Image job database directory mismatch" }
                }
                connection.usePrepared("PRAGMA synchronous=FULL") { it.step() }
                connection.usePrepared("PRAGMA synchronous") { check(it.step() && it.getLong(0) == 2L) { "SQLite FULL sync unavailable" } }
                val admitted = connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    authorizeAdmission()
                    val result = db.imageJobs().admit(request, now())
                    if (result.created) {
                        reservation = assets.reserveImageQuotaWithGateHeld()
                        val journal = reservations.record(result.job.id, assets.root, reservation!!, now())
                        reservations.insert(journal)
                        reservationJournal = journal
                        result.copy(job = db.imageJobs().markRunning(result.job.id, now()))
                    } else result
                }
                // Record the commit before fsync: even an fsync failure must retire this job.
                if (admitted.created) committedId = admitted.job.id
                // Persist directory entries for the DB/WAL before a provider can charge.
                if (admitted.created) syncStore(storeDirectory)
                admitted
            }
        } } } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { committedId?.let { markIfRunning(it, "Image request did not start; local preparation failed. No automatic retry.", false) } }
                finally { reservation?.let { held -> releaseReservation(held, reservationJournal) } }
            }
            throw failure
        }
        if (!admission.created) {
            onAdmitted(admission.job)
            return admission.job
        }
        val credentials = requireNotNull(admission.credentials)
        val worker = Job(currentCoroutineContext()[Job])
        try {
            currentCoroutineContext().ensureActive()
            onAdmitted(admission.job)
            currentCoroutineContext().ensureActive()
            check(active.putIfAbsent(admission.job.id, worker) == null) { "Image job already active" }
            return withContext(worker) {
                var providerSucceeded = false
                try {
                    // Explicit cancellation may have won between admission and registration.
                    val running = assets.withExclusive { db.imageJobs().read(admission.job.id) }
                    if (running.status != ImageJobStatus.running) return@withContext running
                    val response = transport.generate(running, credentials)
                    providerSucceeded = true
                    val output = response.data.map { image ->
                        val bytesAndMime = if (image.base64 != null) {
                            require(image.base64.length <= ((20L * 1024 * 1024 + 2) / 3 * 4)) { "Provider image exceeds limit" }
                            val bytes = Base64.getDecoder().decode(image.base64)
                            bytes to detectImageMime(bytes)
                        } else downloader.download(requireNotNull(image.url))
                        val (bytes, mime) = bytesAndMime
                        val dimensions = validateOwnedImage(bytes, mime)
                        require(image.revisedPrompt?.encodeToByteArray()?.size?.let { it <= 32_000 } != false) { "Revised prompt exceeds limit" }
                        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                        val metadata = CoreAssetMetadata(id, id, mime, bytes.size.toLong(), dimensions.width, dimensions.height, now(), emptyList())
                        Triple(ImageSuccessOutput(metadata, image.revisedPrompt), bytes, mime)
                    }
                    assets.withExclusive {
                        val current = db.imageJobs().read(running.id)
                        if (current.status != ImageJobStatus.running) return@withExclusive current
                        val journal = reservationJournal
                        db.useWriterConnection { connection ->
                            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                                reservation?.let { held -> assets.releaseImageQuotaWithGateHeld(held) }
                                if (journal != null) reservations.delete(journal.jobId)
                            }
                        }
                        reservation = null
                        reservationJournal = null
                        assets.syncDirectoryWithGateHeld()
                        for ((result, bytes) in output) assets.stageVerifiedFile(result.metadata, bytes)
                        assets.syncDirectoryWithGateHeld()
                        db.imageJobs().complete(running.id, output.map { it.first }, response.usage, now())
                    }
                } catch (cancel: CancellationException) {
                    withContext(NonCancellable) { markIfRunning(admission.job.id, "Interrupted; provider charges may have occurred. No automatic retry.", true) }
                    throw cancel
                } catch (failure: Exception) {
                    val detail = (failure.message ?: "Image generation failed").replace(credentials.apiKey, "[redacted]")
                    val unknown = failure is ImagesOutcomeUnknown || (failure is ImagesHttpFailure && failure.status >= 500)
                    val message = when {
                        providerSucceeded -> "Provider responded successfully, but local processing failed; charges may have occurred. No automatic retry. $detail"
                        unknown -> "Provider outcome is unknown; charges may have occurred. No automatic retry. $detail"
                        else -> "Image request failed. No automatic retry. $detail"
                    }
                    markIfRunning(admission.job.id, message, unknown)
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { markIfRunning(admission.job.id, "Interrupted; provider charges may have occurred. No automatic retry.", true) }
            throw cancel
        } finally {
            active.remove(admission.job.id, worker)
            worker.complete()
            withContext(NonCancellable) {
                reservation?.let { held -> releaseReservation(held, reservationJournal) }
            }
        }
    }

    suspend fun cancel(id: String): ImageJob {
        val result = assets.withExclusive { db.imageJobs().cancel(id, now()) }
        active[id]?.cancel()
        return result
    }

    private suspend fun releaseReservation(path: Path, journal: ImageReservationRecord?) {
        try { assets.withExclusive {
            db.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    assets.releaseImageQuotaWithGateHeld(path)
                    if (journal != null) reservations.delete(journal.jobId)
                }
            }
            assets.syncDirectoryWithGateHeld()
        } }
        catch (failure: Throwable) { recoveryRequired.set(true); throw failure }
    }

    private suspend fun markIfRunning(id: String, message: String, unknown: Boolean): ImageJob = try {
        assets.withExclusive {
            val current = db.imageJobs().read(id)
            if (current.status == ImageJobStatus.running) db.imageJobs().fail(id, message.take(64 * 1024), unknown, now()) else current
        }
    } catch (failure: Throwable) {
        recoveryRequired.set(true)
        throw failure
    }

    private fun requireDurableDirectory(directory: Path) {
        requirePrivateAssetRoot(directory)
    }
}

private fun detectImageMime(bytes: ByteArray): String = when {
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    else -> error("Unsupported image format")
}

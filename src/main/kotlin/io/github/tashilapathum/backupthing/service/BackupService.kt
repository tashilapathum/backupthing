// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import io.github.tashilapathum.backupthing.bandwidth.UploadBudget
import io.github.tashilapathum.backupthing.bandwidth.UploadThrottle
import io.github.tashilapathum.backupthing.bandwidth.Usage
import io.github.tashilapathum.backupthing.config.AppDirs
import io.github.tashilapathum.backupthing.config.BandwidthSettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SettingsStore
import io.github.tashilapathum.backupthing.config.SettingsValidator
import io.github.tashilapathum.backupthing.db.BackupDatabase
import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.db.Media_file
import io.github.tashilapathum.backupthing.db.Run
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.deps.DependencyReport
import io.github.tashilapathum.backupthing.encode.MediaEncoder
import io.github.tashilapathum.backupthing.encode.Tools
import io.github.tashilapathum.backupthing.pipeline.Pipeline
import io.github.tashilapathum.backupthing.pipeline.PipelineEvent
import io.github.tashilapathum.backupthing.pipeline.PipelineState
import io.github.tashilapathum.backupthing.pipeline.RunSummary
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.scheduler.Scheduler
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.Uploader
import io.github.tashilapathum.backupthing.upload.Uploaders
import io.github.tashilapathum.backupthing.util.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a run needs; rebuilt for every run so settings and tool changes apply. */
class RunSetup(
    val pipeline: Pipeline,
    val uploader: Uploader,
)

/** Builds a [RunSetup] or explains why a run is impossible right now. */
fun interface RunEnvironment {
    suspend fun prepare(settings: Settings): Result<RunSetup>

    /** Settings were saved; apply what can change during a run (the upload speed limit). */
    fun applyLive(settings: Settings) {}
}

class ProductionEnvironment(
    private val dirs: AppDirs,
    private val db: BackupDatabase,
    private val throttle: UploadThrottle = UploadThrottle(),
) : RunEnvironment {
    override fun applyLive(settings: Settings) = throttle.apply(settings.bandwidth)

    override suspend fun prepare(settings: Settings): Result<RunSetup> =
        runCatching {
            val report = DependencyCheck(settings.system).run()
            val tools = Tools.from(report) ?: error("avifenc, ffmpeg or ffprobe not found")
            check(report.workingVideoBackends.isNotEmpty()) { "No working AV1 encoder" }
            val cli = report.protonDrive.path ?: error("proton-drive CLI not found")
            val guard = PathGuard(dirs.staging(settings))
            RunSetup(
                Pipeline(db, MediaEncoder(tools, guard, report.workingVideoBackends), guard),
                throttle.uploader(settings, cli),
            )
        }
}

data class Totals(
    val doneFiles: Long,
    val doneSourceBytes: Long,
    val doneStoredBytes: Long,
    val pendingFiles: Long,
    val failedFiles: Long,
)

/**
 * The long-running core behind `serve`: owns runs (one at a time), the scheduler, and the state the
 * dashboard shows.
 */
class BackupService(
    private val store: SettingsStore,
    private val db: BackupDatabase,
    private val environment: RunEnvironment,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Where staged files live for given settings (cleared by [resetDatabase]); null disables that cleanup. */
    private val stagingOf: ((Settings) -> java.nio.file.Path)? = null,
    samplesDir: java.nio.file.Path =
        java.nio.file.Files
            .createTempDirectory("backupthing-samples"),
) {
    private val log = LoggerFactory.getLogger(BackupService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val q = db.mediaQueries

    private val _pipelineState = MutableStateFlow(PipelineState())
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()
    private val _auth = MutableStateFlow<AuthStatus?>(null)
    val auth: StateFlow<AuthStatus?> = _auth.asStateFlow()

    private val _deps = MutableStateFlow<DependencyReport?>(null)
    val deps: StateFlow<DependencyReport?> = _deps.asStateFlow()

    val login = ProtonLogin(scope, onSuccess = { refreshStatus() })
    val samples = SampleTester(samplesDir)

    val scheduler = Scheduler(store.settings, ::lastRunStart, { runOnce("schedule") }, clock)
    val nextRun: StateFlow<Instant?> get() = scheduler.nextRun

    val settings: Settings get() = store.settings.value

    fun start() {
        scheduler.start(scope)
        scope.launch {
            while (true) {
                refreshStatus()
                delay(30.minutes)
            }
        }
        scope.launch {
            store.settings.collect {
                Logging.setLevel(it.system.logLevel)
                environment.applyLive(it)
                updateWarnings()
            }
        }
        scope.launch { refreshDeps() }
        scope.launch {
            while (true) {
                delay(5.seconds)
                try {
                    if (store.reloadIfChanged()) log.info("Settings reloaded from file")
                } catch (e: Exception) {
                    log.warn("Ignoring unreadable config file: {}", e.message)
                    _warnings.value = (baseWarnings() + "Config file could not be read: ${e.message}").distinct()
                }
            }
        }
    }

    fun stop() = scope.cancel()

    val isRunning: Boolean get() = runMutex.isLocked

    /** Starts a run in the background. False if one is already running. */
    fun requestRun(trigger: String): Boolean {
        if (runMutex.isLocked) return false
        scope.launch { runOnce(trigger) }
        return true
    }

    /** Runs now and waits; null when another run is in progress or the run could not start. */
    suspend fun runOnce(trigger: String): RunSummary? {
        if (!runMutex.tryLock()) return null
        try {
            val settings = store.settings.value
            val errors = SettingsValidator.validate(settings)
            if (errors.isNotEmpty()) {
                updateWarnings()
                log.warn("Not running: settings invalid ({})", errors.joinToString { it.field })
                return null
            }
            val setup =
                environment.prepare(settings).getOrElse {
                    _warnings.value = (baseWarnings() + "Cannot run: ${it.message}").distinct()
                    log.warn("Not running: {}", it.message)
                    return null
                }
            val forward =
                scope.launch { setup.pipeline.state.collect { _pipelineState.value = it } }
            val forwardEvents = scope.launch { setup.pipeline.events.collect { _events.emit(it) } }
            try {
                return setup.pipeline.run(settings, setup.uploader, trigger)
            } finally {
                _pipelineState.value = setup.pipeline.state.value
                forward.cancel()
                forwardEvents.cancel()
                refreshStatus(setup.uploader)
            }
        } finally {
            runMutex.unlock()
        }
    }

    fun retry(id: Long): Boolean {
        q.retryOne(clock.millis(), id)
        return requestRun("retry")
    }

    fun retryFailed(): Boolean {
        q.retryFailed(clock.millis())
        return requestRun("retry")
    }

    suspend fun refreshDeps(): DependencyReport = DependencyCheck(settings.system).run().also { _deps.value = it }

    fun startLogin(): Boolean {
        val cli = DependencyCheck.findProtonDrive(settings.system) ?: return false
        login.start(
            listOf(cli.toString(), "auth", "login"),
            mapOf("PROTON_DRIVE_CREDENTIALS_STORE" to settings.destination.credentialStore.cliValue),
        )
        return true
    }

    /** Forgets all files and runs (the next run re-encodes and re-checks everything) and empties staging. */
    fun resetDatabase(): Boolean {
        if (!runMutex.tryLock()) return false
        try {
            q.transaction {
                q.deleteAllMedia()
                q.deleteAllRuns()
            }
            stagingOf
                ?.invoke(settings)
                ?.takeIf {
                    java.nio.file.Files
                        .isDirectory(it)
                }?.let { dir ->
                    val guard = PathGuard(dir)
                    java.nio.file.Files.walk(guard.stagingRoot).use { s ->
                        s
                            .filter {
                                java.nio.file.Files
                                    .isRegularFile(it)
                            }.toList()
                            .forEach { guard.delete(it) }
                    }
                    guard.pruneEmptyDirs()
                }
            log.warn("Database reset from the web UI")
            return true
        } finally {
            runMutex.unlock()
        }
    }

    fun totals(): Totals {
        val rows = q.countByStatus().executeAsList().associateBy { it.status }
        val done = rows[MediaStatus.DONE]
        val pending =
            listOf(MediaStatus.NEW, MediaStatus.ENCODING, MediaStatus.ENCODED, MediaStatus.UPLOADING).sumOf {
                rows[it]?.files
                    ?: 0L
            }
        return Totals(
            done?.files ?: 0L,
            done?.src_bytes ?: 0L,
            done?.out_bytes ?: 0L,
            pending,
            rows[MediaStatus.FAILED]?.files ?: 0L,
        )
    }

    /** Recently scanned photos/videos, newest first (for the sample picker). */
    fun recentSources(limit: Long): List<java.nio.file.Path> =
        q.recentSources(limit).executeAsList().map {
            java.nio.file.Path
                .of(it.source_root)
                .resolve(it.rel_path)
        }

    fun recentRuns(limit: Long = 10): List<Run> = q.recentRuns(limit).executeAsList()

    /** Uploaded bytes today and this month, against the caps in [bandwidth] (the saved ones by default). */
    fun bandwidthUsage(bandwidth: BandwidthSettings = settings.bandwidth): Usage =
        UploadBudget(q, bandwidth, clock, clock.zone).usage()

    fun failedFiles(limit: Long = 50): List<Media_file> = q.recentFailed(limit).executeAsList()

    private fun lastRunStart(): Instant? =
        q
            .lastRunStart()
            .executeAsOne()
            .started
            ?.let(Instant::ofEpochMilli)

    private suspend fun refreshStatus(known: Uploader? = null) =
        withContext(Dispatchers.IO) {
            val uploader =
                known ?: runCatching {
                    Uploaders.create(settings.destination, DependencyCheck.findProtonDrive(settings.system))
                }.getOrNull()
            _auth.value = uploader?.authStatus()
            updateWarnings()
        }

    private fun baseWarnings(): List<String> =
        buildList {
            if (!store.isConfigured) add("No configuration yet. Open Settings to set up the source folders.")
            SettingsValidator.validate(settings).forEach { add("Settings: ${it.field}: ${it.message}") }
            if (settings.bandwidth.uploadLimitKBps > 0 && UploadThrottle.environmentHasProxy()) {
                add("The upload speed limit is off because HTTPS_PROXY is already set for Backupthing.")
            }
            when (val a = _auth.value) {
                is AuthStatus.Failed -> {
                    add("Proton: ${a.message}")
                }

                else -> {}
            }
        }

    private fun updateWarnings() {
        _warnings.value = baseWarnings()
    }
}

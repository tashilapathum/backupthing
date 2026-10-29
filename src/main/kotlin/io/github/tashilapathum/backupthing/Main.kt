// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.path
import io.github.tashilapathum.backupthing.bandwidth.UploadThrottle
import io.github.tashilapathum.backupthing.classify.Classifier
import io.github.tashilapathum.backupthing.config.AppDirs
import io.github.tashilapathum.backupthing.config.SettingsStore
import io.github.tashilapathum.backupthing.config.SettingsValidator
import io.github.tashilapathum.backupthing.db.Database
import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.encode.EncodeException
import io.github.tashilapathum.backupthing.encode.EncodeResult
import io.github.tashilapathum.backupthing.encode.MediaEncoder
import io.github.tashilapathum.backupthing.encode.Tools
import io.github.tashilapathum.backupthing.pipeline.Pipeline
import io.github.tashilapathum.backupthing.pipeline.RunLock
import io.github.tashilapathum.backupthing.pipeline.RunStatus
import io.github.tashilapathum.backupthing.pipeline.RunSummary
import io.github.tashilapathum.backupthing.process.ProcessRunner
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.scan.Scanner
import io.github.tashilapathum.backupthing.scan.SourceUnavailableException
import io.github.tashilapathum.backupthing.service.BackupService
import io.github.tashilapathum.backupthing.service.ProductionEnvironment
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.Uploaders
import io.github.tashilapathum.backupthing.util.Logging
import io.github.tashilapathum.backupthing.web.startServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class AppContext(
    val dirs: AppDirs,
    val store: SettingsStore,
)

class Backupthing : CliktCommand(name = "backupthing") {
    init {
        versionOption(BuildInfo.version)
    }

    private val config by option(
        "--config",
        help = "Config file (default: \$XDG_CONFIG_HOME/backupthing/config.toml)",
    ).path()

    override fun help(context: Context) = "Compress phone media and back it up to Proton Drive."

    override fun run() {
        val dirs = AppDirs.resolve(config)
        val store = SettingsStore(dirs.configFile)
        Logging.setLevel(store.settings.value.system.logLevel)
        currentContext.findOrSetObject { AppContext(dirs, store) }
    }
}

class CheckCommand : CliktCommand(name = "check") {
    private val app by requireObject<AppContext>()

    override fun help(context: Context) = "Report external tools, working video encoders and config problems."

    override fun run() {
        val settings = app.store.settings.value
        val report = runBlocking { DependencyCheck(settings.system).run() }
        echo("Tools:")
        report.tools.forEach { t ->
            echo(
                "  ${if (t.found) "ok     " else "MISSING"} ${t.name.padEnd(
                    13,
                )} ${t.path ?: ""}  ${t.version ?: ""}".trimEnd(),
            )
        }
        echo("Video backends (working): ${report.workingVideoBackends.joinToString().ifEmpty { "none" }}")
        val auth =
            report.protonDrive.path?.let { cli ->
                runBlocking { Uploaders.create(settings.destination, cli).authStatus() }
            }
        echo(
            "Proton: " +
                when (auth) {
                    null -> "CLI not installed"
                    AuthStatus.Ok -> "logged in (destination: ${settings.destination.mode.name.lowercase()})"
                    is AuthStatus.Failed -> "${auth.kind}: ${auth.message}"
                },
        )
        echo(
            "Config: ${app.dirs.configFile}${if (app.store.isConfigured) "" else " (not created yet, using defaults)"}",
        )
        val errors = SettingsValidator.validate(settings)
        errors.forEach { echo("  ! ${it.field}: ${it.message}") }
        if (errors.isEmpty()) echo("  valid")
        if (!report.canEncode || auth != AuthStatus.Ok || errors.isNotEmpty()) throw ProgramResult(1)
    }
}

class ScanCommand : CliktCommand(name = "scan") {
    private val app by requireObject<AppContext>()

    override fun help(context: Context) = "Scan source folders and record new or changed media (no encoding)."

    override fun run() {
        val settings = app.store.settings.value
        if (settings.sources.roots.isEmpty()) {
            echo("No source folders configured in ${app.dirs.configFile}", err = true)
            throw ProgramResult(1)
        }
        val (db, driver) = Database.open(app.dirs.database)
        driver.use {
            val result =
                try {
                    Scanner(db, settings.sources).scan()
                } catch (e: SourceUnavailableException) {
                    echo(e.message, err = true)
                    throw ProgramResult(1)
                }
            echo(
                "Added ${result.added}, changed ${result.changed}, unchanged ${result.unchanged}, " +
                    "still settling ${result.unsettled}, ignored ${result.ignored}",
            )
            db.mediaQueries.countByStatus().executeAsList().forEach {
                echo("  ${it.status.name.padEnd(10)} ${it.files} files, ${(it.src_bytes ?: 0) / 1_000_000} MB")
            }
        }
    }
}

class EncodeCommand : CliktCommand(name = "encode") {
    private val app by requireObject<AppContext>()
    private val files by argument().path(mustExist = true, canBeDir = false).multiple(required = true)

    override fun help(context: Context) =
        "Encode files into staging/dev-encode and report sizes (for testing settings)."

    override val hiddenFromHelp = true

    override fun run() {
        val settings = app.store.settings.value
        val report = runBlocking { DependencyCheck(settings.system).run() }
        val tools =
            Tools.from(report) ?: throw ProgramResult(1).also { echo("Missing avifenc/ffmpeg/ffprobe", err = true) }
        val guard = PathGuard(app.dirs.staging(settings).resolve("dev-encode"))
        val encoder = MediaEncoder(tools, guard, report.workingVideoBackends)
        val classifier = Classifier(settings.sources)
        var failed = false
        for (file in files) {
            val kind = classifier.classify(file)
            if (kind == null) {
                echo("${file.fileName}: ignored (extension not configured)")
                continue
            }
            val inSize =
                java.nio.file.Files
                    .size(file)
            try {
                when (val r = runBlocking { encoder.encode(file, file.fileName.toString(), kind, settings) {} }) {
                    is EncodeResult.Encoded -> {
                        echo(
                            "${file.fileName} [$kind${r.backend?.let {
                                ", $it"
                            } ?: ""}]: ${inSize / 1000} kB → ${r.size / 1000} kB " +
                                "(${"%.0f".format(100.0 * r.size / inSize)}%) in ${r.encodeMs} ms → ${r.output}",
                        )
                    }

                    is EncodeResult.UseOriginal -> {
                        echo("${file.fileName} [$kind]: original kept (${r.reason})")
                    }
                }
            } catch (e: EncodeException) {
                failed = true
                echo("${file.fileName}: FAILED ${e.message}", err = true)
            }
        }
        if (failed) throw ProgramResult(1)
    }
}

class RunCommand : CliktCommand(name = "run") {
    private val app by requireObject<AppContext>()
    private val dryRun by option("--dry-run", help = "Encode into staging but do not upload").flag()

    override fun help(context: Context) = "Scan, compress and upload once, then exit."

    override fun run() {
        val settings = app.store.settings.value
        SettingsValidator.validate(settings).takeIf { it.isNotEmpty() }?.let { errors ->
            errors.forEach { echo("${it.field}: ${it.message}", err = true) }
            throw ProgramResult(1)
        }
        val report = runBlocking { DependencyCheck(settings.system).run() }
        val tools = Tools.from(report)
        if (tools == null || report.workingVideoBackends.isEmpty()) {
            echo("Missing encoders. Run `backupthing check`.", err = true)
            throw ProgramResult(1)
        }
        if (!dryRun && !report.protonDrive.found) {
            echo("proton-drive CLI not found. Install it or use --dry-run.", err = true)
            throw ProgramResult(1)
        }
        val lock =
            RunLock.tryAcquire(app.dirs.lockFile) ?: run {
                echo("Another backupthing process is running", err = true)
                throw ProgramResult(1)
            }
        val (db, driver) = Database.open(app.dirs.database)
        // Virtual threads do not keep the JVM alive; closing still frees the port promptly.
        UploadThrottle().use { throttle ->
            lock.use {
                driver.use {
                    val guard = PathGuard(app.dirs.staging(settings))
                    val pipeline = Pipeline(db, MediaEncoder(tools, guard, report.workingVideoBackends), guard)
                    val uploader = throttle.uploader(settings, report.protonDrive.path)
                    val summary =
                        runCancellableOnShutdown { pipeline.run(settings, uploader, trigger = "cli", dryRun = dryRun) }
                    printSummary(summary)
                    if (dryRun) {
                        db.mediaQueries.selectWork(MediaStatus.ENCODED).executeAsList().forEach { row ->
                            val name = row.out_rel_path?.substringAfterLast('/') ?: row.rel_path.substringAfterLast('/')
                            echo(
                                "  would upload ${row.rel_path} → ${uploader.remotePath(
                                    row.rel_path.substringBeforeLast('/', ""),
                                    name,
                                )}",
                            )
                        }
                    }
                    throw ProgramResult(
                        when (summary.status) {
                            // Files left for the next day or month by a cap the user set: not an error.
                            RunStatus.SUCCESS, RunStatus.CAPPED -> 0

                            RunStatus.AUTH_REQUIRED -> 3

                            RunStatus.OFFLINE -> 4

                            RunStatus.CANCELLED -> 130

                            RunStatus.PARTIAL, RunStatus.FAILED -> 1
                        },
                    )
                }
            }
        }
    }

    private fun printSummary(s: RunSummary) {
        val saved = if (s.bytesIn > 0) " (${100 - 100 * s.bytesOut / s.bytesIn}% saved)" else ""
        echo(
            "${s.status}: encoded ${s.encoded}, originals ${s.keptOriginal}, uploaded ${s.uploaded}, " +
                "already there ${s.alreadyPresent}, failed ${s.failed}; " +
                "${s.bytesIn / 1_000_000} MB → ${s.bytesOut / 1_000_000} MB$saved",
        )
        s.message?.let { echo(it) }
    }
}

/** Runs [block], cancelling it cleanly (child processes killed, rows reverted) on Ctrl-C / SIGTERM. */
private fun <T> runCancellableOnShutdown(block: suspend () -> T): T =
    runBlocking {
        val job = async { block() }
        val hook =
            Thread {
                job.cancel()
                runBlocking { withTimeoutOrNull(15.seconds) { job.join() } }
            }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            job.await()
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }

class ServeCommand : CliktCommand(name = "serve") {
    private val app by requireObject<AppContext>()

    override fun help(context: Context) = "Run the scheduler and web dashboard."

    override fun run() {
        val lock =
            RunLock.tryAcquire(app.dirs.lockFile) ?: run {
                echo("Another backupthing process is running", err = true)
                throw ProgramResult(1)
            }
        val (db, driver) = Database.open(app.dirs.database)
        val service =
            BackupService(
                app.store,
                db,
                ProductionEnvironment(app.dirs, db),
                stagingOf = app.dirs::staging,
                samplesDir =
                    java.nio.file.Files
                        .createDirectories(app.dirs.defaultStaging.resolveSibling("samples")),
            )
        val web = app.store.settings.value.web
        service.start()
        val server = startServer(service, web.bindAddress, web.port, { app.store.isConfigured }, app.store::save)
        echo("Dashboard: ${web.dashboardUrl}")
        if (!app.store.isConfigured) echo("No configuration yet. Open the dashboard to set up.")
        val stopped = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
                service.stop()
                runBlocking {
                    withTimeoutOrNull(
                        15.seconds,
                    ) { while (service.isRunning) kotlinx.coroutines.delay(100) }
                }
                driver.close()
                lock.close()
                stopped.countDown()
            },
        )
        stopped.await()
    }
}

class OpenCommand : CliktCommand(name = "open") {
    private val app by requireObject<AppContext>()

    override fun help(context: Context) = "Open the dashboard in the browser, starting the service if it isn't running."

    override fun run() {
        val url = app.store.settings.value.web.dashboardUrl
        val uri = URI(url)
        if (!dashboardUp(uri)) {
            val started =
                runCatching {
                    runBlocking {
                        ProcessRunner.run(
                            listOf("systemctl", "--user", "start", "backupthing.service"),
                            timeout = 30.seconds,
                        )
                    }
                }.getOrNull()
            if (started?.ok != true) {
                fail("Backupthing isn't running. Start it with `backupthing serve` or install the service.")
            }
            val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
            while (!dashboardUp(uri)) {
                if (System.nanoTime() > deadline) {
                    fail("The service started but the dashboard isn't answering. See: journalctl --user -u backupthing")
                }
                Thread.sleep(500)
            }
        }
        echo(url)
        if (System.getenv("WAYLAND_DISPLAY").isNullOrEmpty() && System.getenv("DISPLAY").isNullOrEmpty()) return
        // Not waited for: some xdg-open setups keep running as long as the browser does.
        val browser =
            runCatching {
                ProcessBuilder("xdg-open", url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrElse { fail("Couldn't run xdg-open. Open $url in your browser.") }
        if (browser.waitFor(5, TimeUnit.SECONDS) && browser.exitValue() != 0) {
            fail("xdg-open failed. Open $url in your browser.")
        }
    }

    private fun dashboardUp(uri: URI): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(uri.host, uri.port), 500) } }.isSuccess

    /** Prints [message] and, since the launcher has no terminal, also shows it as a desktop notification. */
    private fun fail(message: String): Nothing {
        echo(message, err = true)
        runCatching {
            ProcessBuilder("notify-send", "--app-name=Backupthing", "--icon=backupthing", "Backupthing", message)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(5, TimeUnit.SECONDS)
        }
        throw ProgramResult(1)
    }
}

fun main(args: Array<String>) {
    // Under systemd, journald timestamps every line already.
    if (System.getenv("JOURNAL_STREAM") != null) System.setProperty("BT_LOG_PATTERN", "%-5level %logger{20} - %msg%n")
    Backupthing()
        .subcommands(
            ServeCommand(),
            RunCommand(),
            OpenCommand(),
            ScanCommand(),
            CheckCommand(),
            EncodeCommand(),
        ).main(args)
}

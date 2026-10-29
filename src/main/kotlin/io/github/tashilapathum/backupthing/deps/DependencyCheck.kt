// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.deps

import io.github.tashilapathum.backupthing.config.SystemSettings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.process.ProcessRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

data class Tool(
    val name: String,
    val path: Path?,
    val version: String?,
) {
    val found: Boolean get() = path != null
}

data class DependencyReport(
    val avifenc: Tool,
    val ffmpeg: Tool,
    val ffprobe: Tool,
    val protonDrive: Tool,
    /** Backends that completed a one-frame test encode, in preference order. */
    val workingVideoBackends: List<VideoBackend>,
) {
    val tools: List<Tool> get() = listOf(avifenc, ffmpeg, ffprobe, protonDrive)
    val canEncode: Boolean get() = avifenc.found && ffmpeg.found && ffprobe.found && workingVideoBackends.isNotEmpty()
}

class DependencyCheck(
    private val system: SystemSettings,
) {
    suspend fun run(): DependencyReport =
        coroutineScope {
            val avifenc = async { tool("avifenc", system.avifencPath, listOf("--version")) }
            val ffmpeg = async { tool("ffmpeg", system.ffmpegPath, listOf("-hide_banner", "-version")) }
            val ffprobe = async { tool("ffprobe", system.ffprobePath, listOf("-hide_banner", "-version")) }
            val proton = async { tool("proton-drive", system.protonDrivePath, listOf("--version")) }
            val ff = ffmpeg.await()
            val backends = ff.path?.let { probeVideoBackends(it) } ?: emptyList()
            DependencyReport(avifenc.await(), ff, ffprobe.await(), proton.await(), backends)
        }

    /** Encoder availability in the build is not enough (no GPU, no driver), so each backend encodes one test frame. */
    suspend fun probeVideoBackends(ffmpeg: Path): List<VideoBackend> =
        coroutineScope {
            PROBE_ORDER
                .map { backend ->
                    async {
                        val cmd =
                            listOf(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error") +
                                backendInputArgs(backend) +
                                listOf("-f", "lavfi", "-i", "testsrc2=size=256x256:rate=1", "-frames:v", "1") +
                                backendEncodeArgs(backend) + listOf("-f", "null", "-")
                        val ok = runCatching { ProcessRunner.run(cmd, timeout = 20.seconds).ok }.getOrDefault(false)
                        backend.takeIf { ok }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    private suspend fun tool(
        name: String,
        override: String,
        versionArgs: List<String>,
    ): Tool {
        val path = if (override.isNotEmpty()) Path.of(override).takeIf { Files.isExecutable(it) } else findOnPath(name)
        val version =
            path?.let {
                runCatching { ProcessRunner.run(listOf(it.toString()) + versionArgs, timeout = 10.seconds) }
                    .getOrNull()
                    ?.let { r -> (r.stdout + r.stderr).lineSequence().firstOrNull { l -> l.isNotBlank() }?.trim() }
            }
        return Tool(name, path, version)
    }

    companion object {
        val PROBE_ORDER = listOf(VideoBackend.NVENC, VideoBackend.QSV, VideoBackend.VAAPI, VideoBackend.SVT_AV1)

        fun findProtonDrive(system: SystemSettings): Path? =
            if (system.protonDrivePath.isNotEmpty()) Path.of(system.protonDrivePath) else findOnPath("proton-drive")

        fun findOnPath(name: String): Path? =
            System
                .getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { Path.of(it, name) }
                .firstOrNull { Files.isExecutable(it) && !Files.isDirectory(it) }

        fun backendInputArgs(backend: VideoBackend): List<String> =
            when (backend) {
                VideoBackend.VAAPI -> listOf("-vaapi_device", "/dev/dri/renderD128")
                else -> emptyList()
            }

        fun backendEncodeArgs(backend: VideoBackend): List<String> =
            when (backend) {
                VideoBackend.NVENC -> listOf("-c:v", "av1_nvenc")
                VideoBackend.QSV -> listOf("-pix_fmt", "nv12", "-c:v", "av1_qsv")
                VideoBackend.VAAPI -> listOf("-vf", "format=nv12,hwupload", "-c:v", "av1_vaapi")
                VideoBackend.SVT_AV1 -> listOf("-c:v", "libsvtav1", "-preset", "12")
                VideoBackend.AUTO -> error("AUTO is not a concrete backend")
            }
    }
}

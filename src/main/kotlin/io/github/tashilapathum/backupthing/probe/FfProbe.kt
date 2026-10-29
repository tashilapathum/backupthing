// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.probe

import io.github.tashilapathum.backupthing.process.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

data class MediaInfo(
    /** Stored (coded) size of the first video/image stream, before any rotation. */
    val width: Int,
    val height: Int,
    /** Display-matrix rotation in degrees (e.g. -90), 0 when absent. */
    val rotation: Int,
    val durationSeconds: Double?,
    val videoCodec: String?,
    val pixFmt: String?,
    val colorRange: String?,
    val colorPrimaries: String?,
    val colorTransfer: String?,
    val colorSpace: String?,
    val audioCodec: String?,
    val creationTime: Instant?,
) {
    val is10Bit: Boolean get() = pixFmt?.contains("10") == true
}

class ProbeException(
    message: String,
) : Exception(message)

class FfProbe(
    private val ffprobe: Path,
) {
    suspend fun probe(file: Path): MediaInfo {
        val r =
            ProcessRunner.run(
                listOf(
                    ffprobe.toString(),
                    "-v",
                    "error",
                    "-print_format",
                    "json",
                    "-show_format",
                    "-show_streams",
                    file.toString(),
                ),
                timeout = 60.seconds,
            )
        if (!r.ok) throw ProbeException("ffprobe failed for ${file.fileName}: ${r.stderr.trim().take(500)}")
        return parse(r.stdout)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): MediaInfo {
            val root = json.parseToJsonElement(text).jsonObject
            val streams = (root["streams"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            val video =
                streams.firstOrNull { it.str("codec_type") == "video" }
                    ?: throw ProbeException("No video/image stream")
            val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
            val format = root["format"]?.jsonObject
            val rotation =
                (video["side_data_list"] as? JsonArray)
                    ?.mapNotNull { it.jsonObject["rotation"]?.jsonPrimitive?.intOrNull }
                    ?.firstOrNull() ?: 0
            val duration =
                video["duration"]?.jsonPrimitive?.doubleOrNull
                    ?: format?.get("duration")?.jsonPrimitive?.doubleOrNull
            val created =
                format
                    ?.get("tags")
                    ?.jsonObject
                    ?.str("creation_time")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return MediaInfo(
                width = video["width"]?.jsonPrimitive?.intOrNull ?: throw ProbeException("No width"),
                height = video["height"]?.jsonPrimitive?.intOrNull ?: throw ProbeException("No height"),
                rotation = rotation,
                durationSeconds = duration,
                videoCodec = video.str("codec_name"),
                pixFmt = video.str("pix_fmt"),
                colorRange = video.str("color_range"),
                colorPrimaries = video.str("color_primaries"),
                colorTransfer = video.str("color_transfer"),
                colorSpace = video.str("color_space"),
                audioCodec = audio?.str("codec_name"),
                creationTime = created,
            )
        }

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}

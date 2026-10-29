// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.upload

import io.github.tashilapathum.backupthing.bandwidth.ThrottleProxy
import io.github.tashilapathum.backupthing.config.DestinationMode
import io.github.tashilapathum.backupthing.config.DestinationSettings
import io.github.tashilapathum.backupthing.process.ProcessResult
import java.nio.file.Path

object Uploaders {
    /**
     * [cliPath] may be null for dry runs; any CLI call then fails as transient. With a [proxy], uploads go
     * through it at [limitKBps], shared by [uploads] parallel uploads.
     */
    fun create(
        destination: DestinationSettings,
        cliPath: Path?,
        proxy: ThrottleProxy? = null,
        limitKBps: Int = 0,
        uploads: Int = 1,
    ): Uploader {
        val cli: CliRunner =
            cliPath?.let { ProtonCli(it, destination.credentialStore, proxy?.url) }
                ?: CliRunner { _, _ -> ProcessResult(127, "", "proton-drive CLI not found") }
        val timeout = { file: Path -> ProtonCli.uploadTimeout(file, if (proxy != null) limitKBps else 0, uploads) }
        return when (destination.mode) {
            DestinationMode.DRIVE -> ProtonDriveUploader(cli, destination.remoteRoot, timeout)
            DestinationMode.PHOTOS -> ProtonPhotosUploader(cli, timeout)
        }
    }
}

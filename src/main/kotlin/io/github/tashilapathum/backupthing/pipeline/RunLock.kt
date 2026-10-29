// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Process-wide lock so `run` and `serve` never work on the same database at once. */
class RunLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        /** Returns null when another process holds the lock. */
        fun tryAcquire(file: Path): RunLock? {
            Files.createDirectories(file.toAbsolutePath().parent)
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = channel.tryLock()
            if (lock == null) {
                channel.close()
                return null
            }
            return RunLock(channel, lock)
        }
    }
}

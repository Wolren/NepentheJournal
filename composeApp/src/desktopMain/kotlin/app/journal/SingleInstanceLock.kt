package app.journal

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * Exclusive OS lock under the data directory, held for the app's lifetime. A second
 * process cannot acquire it, so it cannot race the journal writers.
 */
internal class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        /** Returns null when another process (or another lock in this JVM) holds it. */
        fun acquire(dataDir: File): SingleInstanceLock? {
            runCatching { dataDir.mkdirs() }
            val channel = try {
                FileChannel.open(
                    File(dataDir, "app.lock").toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            } catch (e: Exception) {
                return null
            }
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            } catch (e: Exception) {
                null
            }
            if (lock == null) {
                runCatching { channel.close() }
                return null
            }
            return SingleInstanceLock(channel, lock)
        }
    }
}

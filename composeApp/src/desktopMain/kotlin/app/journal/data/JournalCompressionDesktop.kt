package app.journal.data

import com.github.luben.zstd.Zstd

internal actual object JournalCompression {

    actual val available: Boolean = true

    actual fun compress(data: ByteArray): ByteArray = Zstd.compress(data)

    actual fun decompress(data: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(data)
        require(size in 0..Int.MAX_VALUE.toLong()) { "Journal zstd frame declares an unusable size: $size" }
        return Zstd.decompress(data, size.toInt())
    }
}

package app.journal.export

import app.journal.data.aesGcmOpen
import app.journal.data.aesGcmSeal
import com.github.luben.zstd.Zstd
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal actual object BackupCodec {

    actual val available: Boolean = true

    actual fun compress(data: ByteArray): ByteArray = Zstd.compress(data)

    actual fun decompress(data: ByteArray): ByteArray {
        val size = Zstd.getFrameContentSize(data)
        require(size in 0..Int.MAX_VALUE.toLong()) { "Backup frame declares an unusable size: $size" }
        return Zstd.decompress(data, size.toInt())
    }

    actual fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    actual fun encrypt(plain: ByteArray, key: ByteArray): ByteArray = aesGcmSeal(plain, key)

    actual fun decrypt(sealed: ByteArray, key: ByteArray): ByteArray = aesGcmOpen(sealed, key)
}

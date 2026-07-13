package app.journal.sync

/**
 * Returns the platform-specific directory for storing sync identity
 * and trust store data.
 *
 * Desktop: ~/.psychonautica/
 * Android: <app files dir>/.psychonautica/
 */
expect fun platformSyncDataDir(): String

/**
 * Returns a human-readable device display name for the current platform.
 *
 * Desktop: "${os.name} Desktop"  (e.g. "Windows 10 Desktop")
 * Android: "${Build.MANUFACTURER} ${Build.MODEL}"  (e.g. "Google Pixel 8")
 */
expect fun platformDeviceName(): String

/**
 * Generates a self-signed RSA 2048-bit X.509 certificate and stores
 * it in a new PKCS12 keystore at [storePath].
 *
 * Uses BouncyCastle internally — works on both desktop JVM and Android.
 *
 * @param storePath absolute path for the output .p12 file
 * @param alias key alias in the keystore
 * @param password keystore and private key password
 */
expect fun generateSelfSignedP12(
    storePath: String,
    alias: String,
    password: CharArray
)

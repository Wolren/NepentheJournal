package app.journal.data

import app.journal.util.crypto.sha256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Startup trusts [SEED_BUNDLED_FINGERPRINT] instead of hashing 13 MB of bundled
 * JSON on every launch, so the constant has to equal the bytes it stands for.
 * Regenerate with the generateSeedFingerprint Gradle task (runs on every build).
 */
class SeedFingerprintTest {

    @Test
    fun fingerprintMatchesTheBundledResources() {
        val resources = bundledResourcesDir()
        val seed = File(resources, "psychonautwiki_seed.json").readText(Charsets.UTF_8)
        val dose = File(resources, "dosewiki_slim.json").readText(Charsets.UTF_8)
        val hex = sha256((seed + dose).encodeToByteArray())
            .joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
        assertEquals(
            "${seed.length}:${dose.length}:$hex",
            SEED_BUNDLED_FINGERPRINT,
            "the generated seed fingerprint drifted from the bundled resources"
        )
    }

    private fun bundledResourcesDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf("composeApp/src/desktopMain/resources", "src/desktopMain/resources")) {
                val resources = File(dir, candidate)
                if (File(resources, "psychonautwiki_seed.json").isFile) return resources
            }
            dir = dir.parentFile
        }
        fail("bundled resources not found from ${System.getProperty("user.dir")}")
    }
}

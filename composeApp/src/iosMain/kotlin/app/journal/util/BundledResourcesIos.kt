package app.journal.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.native.ObjCName
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalObjCName::class)
actual fun readBundledResource(path: String): String? {
    return try {
        val cleanPath = path.trimStart('/')
        val name = cleanPath.substringBeforeLast('.')
        val ext = cleanPath.substringAfterLast('.', "")
        // On iOS all bundled resources are in the main app bundle
        val bundleRoot = NSBundle.mainBundle.pathForResource(name, ofType = ext)
        // The Kotlin/Native test runner is a bare executable, not an app
        // bundle: copyTestComposeResourcesForIosSimulatorArm64 copies the
        // resources next to the test kexe under compose-resources/, which
        // pathForResource(name, ofType:) cannot see (it only searches the
        // bundle root). Candidates are tried in order; the first file that
        // reads wins, so app-bundle lookups keep their original behavior.
        val exeDir = NSBundle.mainBundle.executablePath?.substringBeforeLast('/')
        val candidates = listOfNotNull(
            bundleRoot,
            NSBundle.mainBundle.pathForResource(name, ofType = ext, inDirectory = "compose-resources"),
            exeDir?.let { "$it/compose-resources/$cleanPath" },
            exeDir?.let { "$it/$cleanPath" },
            "${NSBundle.mainBundle.bundlePath}/compose-resources/$cleanPath",
        )
        for (candidate in candidates) {
            val content = NSString.stringWithContentsOfFile(
                candidate, encoding = NSUTF8StringEncoding, error = null
            )
            if (content != null) return content
        }
        null
    } catch (_: Exception) { null }
}

/** Anchor class to locate the framework bundle at runtime. */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@ObjCName("BundledResourceAnchor")
class BundledResourceAnchor

package app.journal.util

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * PlatformDetect: the desktop target's actual must report true in the
 * desktopTest run (audit section 4, cheap pure additions). Android/iOS
 * actuals return false but cannot execute on this host.
 */
class PlatformDetectTest {

    @Test
    fun desktopTargetReportsDesktop() {
        assertTrue(isDesktopPlatform(),
            "the desktop actual must identify this platform as desktop")
    }
}

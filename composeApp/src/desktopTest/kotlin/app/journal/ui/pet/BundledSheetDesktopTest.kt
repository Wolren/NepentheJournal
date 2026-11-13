package app.journal.ui.pet

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledSheetDesktopTest {

    /** Android's unit-test runtime stubs BitmapFactory, so the decode check runs here instead. */
    @Test
    fun theEmbeddedSheetDecodesAndEveryClipFits() {
        val sheet = assertNotNull(BundledPet.sheet(), "the embedded sheet did not decode")
        val columns = sheet.image.width / sheet.frameWidth
        for (clip in sheet.clips) {
            assertTrue(
                (clip.row + 1) * sheet.frameHeight <= sheet.image.height,
                "${clip.name} row ${clip.row} falls past the ${sheet.image.height} px sheet",
            )
            assertTrue(
                clip.frames <= columns,
                "${clip.name} asks for ${clip.frames} frames, the sheet has $columns columns",
            )
        }
    }
}

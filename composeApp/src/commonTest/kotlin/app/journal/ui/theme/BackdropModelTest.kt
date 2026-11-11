package app.journal.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class BackdropModelTest {

    @Test
    fun aWindowsDrivePathBecomesAFileUri() {
        assertEquals("file:///D:/pictures/wall.png", backdropModel("D:/pictures/wall.png"))
    }

    @Test
    fun backslashesAreNormalised() {
        assertEquals("file:///D:/pictures/wall.png", backdropModel("D:\\pictures\\wall.png"))
    }

    @Test
    fun aUrlIsLeftAlone() {
        assertEquals("https://example.com/wall.png", backdropModel("https://example.com/wall.png"))
    }

    @Test
    fun aContentUriIsLeftAlone() {
        assertEquals("content://media/1", backdropModel("content://media/1"))
    }
}

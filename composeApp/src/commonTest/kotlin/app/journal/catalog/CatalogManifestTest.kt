package app.journal.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The extracted catalog copy is trusted only when its byte size matches the
 * manifest, so a short copy from a killed launch gets replaced on the next open.
 */
class CatalogManifestTest {

    @Test
    fun readsTheContentVersionAndSizeFromTheBundledManifest() {
        val manifest = """{"content_version":"aa530da504b108d3","sqlite_size_bytes":16277504}"""
        assertEquals("aa530da504b108d3", CatalogSource.versionFrom(manifest))
        assertEquals(16_277_504L, CatalogSource.sizeFrom(manifest))
    }

    @Test
    fun missingOrBrokenManifestFieldsStayNull() {
        assertNull(CatalogSource.versionFrom(null))
        assertNull(CatalogSource.sizeFrom(null))
        assertNull(CatalogSource.sizeFrom("not json"))
        assertNull(CatalogSource.sizeFrom("""{"content_version":"x"}"""))
        assertNull(CatalogSource.sizeFrom("""{"sqlite_size_bytes":"big"}"""))
    }
}

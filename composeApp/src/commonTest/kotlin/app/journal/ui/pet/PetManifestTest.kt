package app.journal.ui.pet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetManifestTest {

    @Test
    fun aManifestWithoutClipsUsesThePetdexGrid() {
        val manifest = PetManifest()
        assertEquals(9, manifest.resolvedClips().size)
        assertEquals(LegacyRowFrames, manifest.resolvedClips().map { it.frames })
    }

    @Test
    fun theGridNamesTheRowsTheCompanionPlays() {
        val manifest = PetManifest()
        assertEquals(0, manifest.clip("idle")?.row)
        assertEquals(3, manifest.clip("wave")?.row)
        assertEquals(7, manifest.clip("run")?.row)
        assertEquals(5, manifest.clip("fail")?.row)
        assertEquals(8, manifest.clip("checkin")?.row)
    }

    @Test
    fun declaredClipsReplaceTheGrid() {
        val manifest = PetManifest(
            clips = listOf(PetClip("idle", 0, 2), PetClip("fail", 1, 3, loop = false)),
        )
        assertEquals(listOf("idle", "fail"), manifest.resolvedClips().map { it.name })
        assertNull(manifest.clip("run"))
    }

    @Test
    fun clipLookupIgnoresCase() {
        assertEquals(6, PetManifest().clip("IDLE")?.frames)
    }

    @Test
    fun everyReactionHasAClipOnEveryManifest() {
        for (manifest in listOf(PetManifest(), BundledPet.manifest)) {
            for (reaction in PetReaction.values()) {
                assertNotNull(
                    manifest.clip(reaction.clipName),
                    "missing ${reaction.clipName} in ${manifest.id.ifBlank { "petdex" }}",
                )
            }
        }
    }

    @Test
    fun theBundledPetDeclaresOnlyRowsInsideItsSheet() {
        val columns = 8
        val rows = 5
        assertEquals(listOf("idle", "wave", "run", "fail", "checkin"), BundledPet.manifest.resolvedClips().map { it.name })
        for (clip in BundledPet.manifest.resolvedClips()) {
            assertTrue(clip.frames <= columns, "${clip.name} has ${clip.frames} frames, the sheet holds $columns")
            assertTrue(clip.row in 0 until rows, "${clip.name} points at row ${clip.row}, the sheet holds $rows")
        }
    }

}

package com.example.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tile names are only unique within a survey. Once the picker covered more than one project,
 * deciding reuse by filename alone could hand back an unrelated survey's file of the same name —
 * silently substituting the wrong ground.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LazDatasetStoreSourceIndexTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): LazDatasetStore = LazDatasetStore(folder.newFolder("lidar"))

    private fun LazDatasetStore.put(name: String): File =
        File(directory, name).apply { writeText("LASF payload") }

    @Test
    fun aRecordedSourceResolvesBackToItsFile() {
        val store = store()
        val file = store.put("tile.laz")

        store.recordSource("https://example.org/a/tile.laz", file)

        assertEquals(file, store.fileForSource("https://example.org/a/tile.laz"))
    }

    /** The bug this exists to prevent: same filename, different survey. */
    @Test
    fun twoSurveysSharingAFilenameStayDistinct() {
        val store = store()
        val fromFirstSurvey = store.put("tile.laz")
        val fromSecondSurvey = store.put("tile-2.laz")

        store.recordSource("https://example.org/survey-a/tile.laz", fromFirstSurvey)
        store.recordSource("https://example.org/survey-b/tile.laz", fromSecondSurvey)

        assertEquals(fromFirstSurvey, store.fileForSource("https://example.org/survey-a/tile.laz"))
        assertEquals(fromSecondSurvey, store.fileForSource("https://example.org/survey-b/tile.laz"))
    }

    @Test
    fun anUnknownSourceResolvesToNothing() {
        assertNull(store().fileForSource("https://example.org/never-fetched.laz"))
    }

    /** A file the user deleted must not be reported as reusable. */
    @Test
    fun aDeletedFileIsNoLongerReusable() {
        val store = store()
        val file = store.put("tile.laz")
        store.recordSource("https://example.org/a/tile.laz", file)

        assertTrue(file.delete())

        assertNull(store.fileForSource("https://example.org/a/tile.laz"))
    }

    @Test
    fun theIndexSurvivesANewStoreInstance() {
        val directory = folder.newFolder("persisted")
        val first = LazDatasetStore(directory)
        val file = File(directory, "tile.laz").apply { writeText("LASF payload") }
        first.recordSource("https://example.org/a/tile.laz", file)

        assertEquals(file, LazDatasetStore(directory).fileForSource("https://example.org/a/tile.laz"))
    }

    @Test
    fun recordingTheSameSourceAgainReplacesTheAssociation() {
        val store = store()
        val original = store.put("tile.laz")
        val replacement = store.put("tile-2.laz")

        store.recordSource("https://example.org/a/tile.laz", original)
        store.recordSource("https://example.org/a/tile.laz", replacement)

        assertEquals(replacement, store.fileForSource("https://example.org/a/tile.laz"))
    }

    /** The index must never be mistaken for a dataset. */
    @Test
    fun theIndexIsNotListedAsADataset() {
        val store = store()
        val file = store.put("tile.laz")
        store.recordSource("https://example.org/a/tile.laz", file)

        assertEquals(listOf("tile.laz"), store.list().map { it.displayName })
    }

    @Test
    fun aFileOutsideTheStoreIsNotRecorded() {
        val store = store()
        val stray = folder.newFile("elsewhere.laz")

        store.recordSource("https://example.org/a/tile.laz", stray)

        assertNull(store.fileForSource("https://example.org/a/tile.laz"))
    }

    @Test
    fun aCorruptIndexIsTreatedAsEmptyRatherThanCrashing() {
        val directory = folder.newFolder("corrupt")
        val store = LazDatasetStore(directory)
        File(directory, ".sources.json").writeText("not json")

        assertNull(store.fileForSource("https://example.org/a/tile.laz"))
    }

    @Test
    fun renamePreservesSourceReuseAcrossStoreInstances() {
        val directory = folder.newFolder("renamed")
        val first = LazDatasetStore(directory)
        val original = File(directory, "tile.laz").apply { writeText("LASF payload") }
        first.recordSource("https://example.org/a/tile.laz", original)

        val renamed = first.rename(first.list().single(), "my hunting area")

        assertEquals("my hunting area.laz", renamed.file.name)
        assertEquals(renamed.file, LazDatasetStore(directory).fileForSource("https://example.org/a/tile.laz"))
    }

    @Test
    fun deleteRemovesSourceReuseAssociation() {
        val store = store()
        val file = store.put("tile.laz")
        store.recordSource("https://example.org/a/tile.laz", file)

        assertTrue(store.delete(store.list().single()))
        assertNull(store.fileForSource("https://example.org/a/tile.laz"))
    }
}

package mano.algo.caster.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeStoreTest {
    @Test
    fun aVideoThatHasHardlyStartedIsNotRemembered() {
        assertFalse(ResumeStore.keeps(3, 3600))
        assertFalse(ResumeStore.keeps(9, 3600))
        assertTrue(ResumeStore.keeps(10, 3600))
    }

    @Test
    fun aVideoThatIsAlmostOverIsForgotten() {
        assertTrue(ResumeStore.keeps(3000, 3600))
        assertFalse(ResumeStore.keeps(3581, 3600))
        assertFalse(ResumeStore.keeps(3600, 3600))
    }

    @Test
    fun anUnknownLengthIsKeptOnceItHasStarted() {
        assertTrue(ResumeStore.keeps(120, 0))
        assertFalse(ResumeStore.keeps(5, 0))
    }
}

class ShareFoldersTest {
    @org.junit.After
    fun back() {
        ShareSettings.setMode(ShareMode.ALL)
        ShareSettings.setFolders(emptySet())
        ShareSettings.setSelected(emptySet())
    }

    @Test
    fun folderModeSharesTheVideosOfTheChosenFoldersOnly() {
        ShareSettings.setFolders(setOf(5L))
        ShareSettings.setMode(ShareMode.FOLDERS)
        assertTrue(ShareSettings.isAllowed(1L, bucketId = 5L))
        assertFalse(ShareSettings.isAllowed(2L, bucketId = 6L))
        // The folder of a video is remembered, so a later question without it gets the same answer.
        assertTrue(ShareSettings.isAllowed(1L))
        // Unknown folder (nothing to look it up with): not shared.
        assertFalse(ShareSettings.isAllowed(3L))
    }

    @Test
    fun theOtherModesIgnoreFolders() {
        ShareSettings.setFolders(setOf(5L))
        ShareSettings.setSelected(setOf(7L))
        ShareSettings.setMode(ShareMode.ALL)
        assertTrue(ShareSettings.isAllowed(9L, bucketId = 99L))
        ShareSettings.setMode(ShareMode.SELECTED)
        assertTrue(ShareSettings.isAllowed(7L, bucketId = 99L))
        assertFalse(ShareSettings.isAllowed(8L, bucketId = 5L))
    }

    @Test
    fun movingToAnotherFolderSetAppliesAtOnce() {
        ShareSettings.setMode(ShareMode.FOLDERS)
        ShareSettings.setFolders(setOf(5L))
        assertTrue(ShareSettings.isAllowed(11L, bucketId = 5L))
        ShareSettings.setFolders(setOf(6L))
        assertFalse(ShareSettings.isAllowed(11L, bucketId = 5L))
        assertEquals(setOf(6L), ShareSettings.state.value.folders)
    }
}

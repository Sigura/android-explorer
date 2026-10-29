package com.mediaviewer.data

import com.mediaviewer.data.mtp.MtpPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpPathsTest {
    @Test fun parsesDeviceStorageAndFolders() {
        val p = MtpPaths.parse("mtp://1003/65537/DCIM/Camera/IMG_1.jpg")!!
        assertEquals(1003, p.deviceId)
        assertEquals(65537, p.storageId)
        assertEquals(listOf("DCIM", "Camera", "IMG_1.jpg"), p.segments)
        assertTrue(MtpPaths.parse(MtpPaths.storageRoot(3, 7))!!.isStorageRoot)
        assertNull(MtpPaths.parse("/storage/emulated/0"))
        assertNull(MtpPaths.parse("mtp://3"))
        assertFalse(MtpPaths.isMtp("/sdcard/mtp://x"))
    }

    @Test fun parentStopsAtTheStorageRoot() {
        assertEquals("mtp://3/7/DCIM", MtpPaths.parent("mtp://3/7/DCIM/a.jpg"))
        assertEquals("mtp://3/7", MtpPaths.parent("mtp://3/7/DCIM"))
        assertNull(MtpPaths.parent("mtp://3/7"))
        assertEquals("mtp://3/7", MtpPaths.rootOf("mtp://3/7/DCIM/a.jpg"))
    }

    @Test fun childNamesNeverAddFolders() {
        assertEquals("mtp://3/7/a_b.jpg", MtpPaths.child("mtp://3/7", "a/b.jpg"))
        assertEquals("mtp://3/7/DCIM", MtpPaths.child("mtp://3/7/", "DCIM"))
    }

    @Test fun mirrorPathsRoundTrip() {
        val path = "mtp://3/7/DCIM/My Trip/a b.mp4"
        val rel = MtpPaths.mirrorRelative(path)!!
        assertEquals("3/7/DCIM/My Trip/a b.mp4", rel)
        assertEquals(path, MtpPaths.fromMirrorRelative("/$rel"))
        assertNull(MtpPaths.fromMirrorRelative("x/y"))
    }
}

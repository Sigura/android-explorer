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
        assertEquals("1003", p.device)
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

    @Test fun deviceKeysAreStableOnlyWithASerialNumber() {
        val k = MtpPaths.deviceKey(0x04a9, 0x32b4, "A1:B2 c3", usbId = 1003)
        assertEquals("04a9-32b4-A1B2c3", k)
        assertTrue(MtpPaths.isRemembered(k))
        assertEquals("04a9-32b4-", MtpPaths.modelOf(k))
        assertEquals(MtpPaths.modelPrefix(0x04a9, 0x32b4), MtpPaths.modelOf(k))
        // The same device plugged in again, on another USB id: the same key.
        assertEquals(k, MtpPaths.deviceKey(0x04a9, 0x32b4, "A1:B2 c3", usbId = 2001))
        val session = MtpPaths.deviceKey(0x04a9, 0x32b4, null, usbId = 1003)
        assertEquals("usb1003", session)
        assertFalse(MtpPaths.isRemembered(session))
        assertEquals("usb7", MtpPaths.deviceKey(1, 2, "::", usbId = 7))
        val path = MtpPaths.child(MtpPaths.storageRoot(k, 65537), "DCIM")
        assertEquals("mtp://04a9-32b4-A1B2c3/65537/DCIM", path)
        assertEquals(k, MtpPaths.deviceOf(path))
        assertTrue(MtpPaths.isRememberedPath(path))
        assertFalse(MtpPaths.isRememberedPath("mtp://usb3/7/DCIM"))
        assertFalse(MtpPaths.isRememberedPath("/storage/emulated/0"))
        assertNull(MtpPaths.parse("mtp://a b/7"))
    }
}

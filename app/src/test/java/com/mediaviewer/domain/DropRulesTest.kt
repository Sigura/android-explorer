package com.mediaviewer.domain

import com.mediaviewer.domain.DropRules.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DropRulesTest {
    @Test fun otherAppsAlwaysCopy() {
        assertEquals(Action.COPY, DropRules.plan(null, "/sd/Pictures", targetIsDevice = false, shift = false).action)
        assertEquals(Action.COPY, DropRules.plan(null, "/sd/Pictures", targetIsDevice = false, shift = true).action)
    }

    @Test fun ownTilesCopyAndMoveWithShift() {
        val own = listOf("/sd/DCIM/a.jpg", "/sd/DCIM/b.jpg")
        val copy = DropRules.plan(own, "/sd/Pictures", targetIsDevice = false, shift = false)
        assertEquals(Action.COPY, copy.action)
        assertEquals(own, copy.paths)
        val move = DropRules.plan(own, "/sd/Pictures/", targetIsDevice = false, shift = true)
        assertEquals(Action.MOVE, move.action)
        assertEquals(own, move.paths)
    }

    @Test fun droppedOnItsOwnFolderDoesNothing() {
        val p = DropRules.plan(listOf("/sd/DCIM/a.jpg"), "/sd/DCIM", targetIsDevice = false, shift = true)
        assertEquals(Action.NOTHING, p.action)
        assertEquals(emptyList<Pair<String, String>>(), p.refused)
        // Only the ones elsewhere take part.
        val mixed = DropRules.plan(listOf("/sd/DCIM/a.jpg", "/sd/Pictures/b.jpg"), "/sd/DCIM", targetIsDevice = false, shift = true)
        assertEquals(Action.MOVE, mixed.action)
        assertEquals(listOf("/sd/Pictures/b.jpg"), mixed.paths)
    }

    @Test fun folderIntoItselfIsRefused() {
        val p = DropRules.plan(listOf("/sd/Trips"), "/sd/Trips/2024", targetIsDevice = false, shift = true)
        assertEquals(Action.NOTHING, p.action)
        assertEquals("/sd/Trips", p.refused.single().first)
        val self = DropRules.plan(listOf("/sd/Trips", "/sd/x.jpg"), "/sd/Trips", targetIsDevice = false, shift = false)
        assertEquals(Action.COPY, self.action)
        assertEquals(listOf("/sd/x.jpg"), self.paths)
        assertEquals(1, self.refused.size)
        // A sibling with a longer name is not "inside".
        assertEquals(Action.COPY, DropRules.plan(listOf("/sd/Trips"), "/sd/Trips2024", targetIsDevice = false, shift = false).action)
    }

    @Test fun deviceFoldersTakeNoDrops() {
        val p = DropRules.plan(listOf("/sd/a.jpg"), "mtp://3/65537/DCIM", targetIsDevice = true, shift = true)
        assertEquals(Action.REFUSE, p.action)
        assertNotNull(p.reason)
        assertEquals(Action.REFUSE, DropRules.plan(null, "mtp://3/65537", targetIsDevice = true, shift = false).action)
    }
}

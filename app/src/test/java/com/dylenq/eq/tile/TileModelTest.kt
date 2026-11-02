package com.dylenq.eq.tile

import com.dylenq.eq.service.ServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileModelTest {
    @Test fun eqOnIsActive() {
        val m = TileModel.from(ServiceStatus(bypass = false))
        assertEquals(TileModel.State.ACTIVE, m.state)
        assertEquals("EQ", m.label)
        assertEquals("On", m.subtitle)
        assertTrue(m.canToggle)
    }

    @Test fun bypassedIsInactive() {
        val m = TileModel.from(ServiceStatus(bypass = true))
        assertEquals(TileModel.State.INACTIVE, m.state)
        assertEquals("EQ", m.label)
        assertEquals("Bypassed", m.subtitle)
        assertTrue(m.canToggle)
    }

    @Test fun unavailableStatusHasNoToggle() {
        val m = TileModel.from(ServiceStatus(unavailable = true, bypass = true))
        assertEquals(TileModel.State.UNAVAILABLE, m.state)
        assertFalse(m.canToggle)
        assertEquals(TileModel.Unavailable, m)
    }
}

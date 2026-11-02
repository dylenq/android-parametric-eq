package com.dylenq.eq.ui

import com.dylenq.eq.service.DeviceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesCopyTest {
    private fun d(key: String, current: Boolean = false, seen: Long = 0, name: String = key) =
        DeviceEntry(key, name, null, current, seen)

    @Test fun currentDeviceComesFirstThenMostRecent() {
        val out = DevicesCopy.sorted(listOf(d("a", seen = 9), d("b", current = true, seen = 1), d("c", seen = 5)))
        assertEquals(listOf("b", "a", "c"), out.map { it.key })
    }

    @Test fun tiesBreakByNameThenKey() {
        val out = DevicesCopy.sorted(listOf(d("2", name = "Zed"), d("1", name = "alpha")))
        assertEquals(listOf("1", "2"), out.map { it.key })
    }

    @Test fun pickerTextIsNoneWithoutPresetOrWhenItIsGone() {
        assertEquals("None (keep current sound)", DevicesCopy.presetText(null, mapOf("x" to "Bass")))
        assertEquals("None (keep current sound)", DevicesCopy.presetText("gone", mapOf("x" to "Bass")))
        assertEquals("Bass", DevicesCopy.presetText("x", mapOf("x" to "Bass")))
    }

    @Test fun noteSaysDevicesWithNoPresetKeepTheCurrentSound() {
        assertTrue(DevicesCopy.NOTE.contains("no preset keep the current sound"))
        assertFalse(DevicesCopy.NOTE.contains("—"))
    }

    @Test fun countText() {
        assertEquals("1 device", DevicesCopy.countText(1))
        assertEquals("2 devices", DevicesCopy.countText(2))
    }
}

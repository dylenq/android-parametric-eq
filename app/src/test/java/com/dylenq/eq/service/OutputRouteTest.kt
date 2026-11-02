package com.dylenq.eq.service

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRouteTest {
    @Test
    fun speaker_maps_to_the_fixed_key_and_name() {
        assertEquals(RouteInfo("speaker", "Phone speaker"), routeInfoFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel"))
    }

    @Test
    fun wired_headset_and_headphones_share_one_rule() {
        val a = routeInfoFor(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Whatever")
        val b = routeInfoFor(AudioDeviceInfo.TYPE_WIRED_HEADSET, null)
        assertEquals(RouteInfo("wired", "Wired headphones"), a)
        assertEquals(a, b)
    }

    @Test
    fun bluetooth_and_usb_carry_the_product_name() {
        assertEquals(RouteInfo("bluetooth:Buds Pro", "Bluetooth: Buds Pro"), routeInfoFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds Pro"))
        assertEquals(RouteInfo("bluetooth:Buds Pro", "Bluetooth: Buds Pro"), routeInfoFor(AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds Pro"))
        assertEquals(RouteInfo("usb:DAC One", "USB: DAC One"), routeInfoFor(AudioDeviceInfo.TYPE_USB_HEADSET, "DAC One"))
    }

    @Test
    fun a_blank_name_falls_back_to_the_type_label() {
        assertEquals(RouteInfo("bluetooth", "Bluetooth"), routeInfoFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "   "))
        assertEquals(RouteInfo("usb", "USB"), routeInfoFor(AudioDeviceInfo.TYPE_USB_DEVICE, null))
    }

    @Test
    fun names_are_trimmed_capped_and_free_of_control_characters() {
        val r = routeInfoFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "  Evil\n\u0007Name " + "x".repeat(200))
        assertFalse(r.name.any { it.isISOControl() })
        assertFalse(r.key.any { it.isISOControl() })
        assertEquals("Bluetooth: ".length + 40, r.name.length)
        assertTrue(r.name.startsWith("Bluetooth: EvilName"))
    }

    @Test
    fun an_unknown_type_still_gets_a_key() {
        assertEquals(RouteInfo("other:9", "Other output"), routeInfoFor(AudioDeviceInfo.TYPE_HDMI, null))
    }

    @Test
    fun priority_is_usb_then_wired_then_bluetooth_then_speaker() {
        val speaker = RouteCandidate(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null)
        val bt = RouteCandidate(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")
        val wired = RouteCandidate(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, null)
        val usb = RouteCandidate(AudioDeviceInfo.TYPE_USB_DEVICE, "DAC")
        assertEquals("usb:DAC", pickRoute(listOf(speaker, bt, wired, usb)).key)
        assertEquals("wired", pickRoute(listOf(speaker, bt, wired)).key)
        assertEquals("bluetooth:Buds", pickRoute(listOf(speaker, bt)).key)
        assertEquals("speaker", pickRoute(listOf(speaker)).key)
    }

    @Test
    fun an_empty_list_is_the_speaker() {
        assertEquals(RouteInfo.SPEAKER, pickRoute(emptyList()))
    }

    // A manual scheduler: nothing runs until the test advances its clock.
    private class FakeClock {
        private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = ArrayList<Task>()
        var now = 0L
        fun schedule(delay: Long, run: () -> Unit): () -> Unit {
            val t = Task(now + delay, run)
            tasks.add(t)
            return { t.cancelled = true }
        }
        fun advance(ms: Long) {
            now += ms
            tasks.filter { !it.cancelled && it.at <= now }.forEach { tasks.remove(it); it.run() }
        }
    }

    @Test
    fun a_burst_of_events_runs_once_after_300_ms() {
        val clock = FakeClock()
        var runs = 0
        val d = Debouncer(Debouncer.ROUTE_DEBOUNCE_MS, clock::schedule) { runs++ }
        d.trigger()
        clock.advance(200)
        d.trigger()
        clock.advance(200)
        assertEquals("the second event pushed the run out", 0, runs)
        clock.advance(100)
        assertEquals(1, runs)
        clock.advance(1000)
        assertEquals(1, runs)
        d.trigger()
        clock.advance(300)
        assertEquals(2, runs)
    }

    @Test
    fun cancel_drops_the_pending_run() {
        val clock = FakeClock()
        var runs = 0
        val d = Debouncer(300, clock::schedule) { runs++ }
        d.trigger()
        d.cancel()
        clock.advance(1000)
        assertEquals(0, runs)
    }
}

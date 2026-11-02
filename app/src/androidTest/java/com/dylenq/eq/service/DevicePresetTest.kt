package com.dylenq.eq.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.testsupport.FakeEffectFactory
import com.dylenq.eq.ui.UiMessage
import com.dylenq.eq.ui.UiStateHolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A device preset reaches the live EQ through the runtime: once, as one undo step, never mid-gesture. */
@RunWith(AndroidJUnit4::class)
class DevicePresetTest {
    private class FakeRoute(@Volatile var info: RouteInfo) : OutputRoute {
        override fun current() = info
    }

    private val buds = RouteInfo("bluetooth:Buds", "Bluetooth: Buds")
    private val wired = RouteInfo("wired", "Wired headphones")

    private val dir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "device-preset").also { it.mkdirs() }

    private val route = FakeRoute(RouteInfo.SPEAKER)
    private val factory = FakeEffectFactory()
    private val opened = ArrayList<EqRuntime>()
    private val holders = ArrayList<UiStateHolder>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun clean() {
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        scope.cancel()
        holders.forEach { it.close() }
        opened.forEach { it.shutdown() }
        dir.deleteRecursively()
    }

    private fun runtime(): EqRuntime =
        EqRuntime(factory, dir, route = route, routeDebounceMs = 20).also { opened.add(it) }

    private fun holderFor(rt: EqRuntime): UiStateHolder = UiStateHolder(rt).also { holders.add(it) }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun gain0(rt: EqRuntime) = rt.controller.snapshot().graphicGainsDb[0]

    /** Seeds a preset "Bright" (band 0 at +6) and leaves the live EQ at -3; returns the preset key. */
    private fun seedBright(rt: EqRuntime): String {
        rt.controller.edit { it.setGraphicGain(0u, 6f) }
        val key = rt.controller.savePreset("Bright")
        rt.controller.edit { it.setGraphicGain(0u, -3f) }
        return key
    }

    /** Takes the first sighting (the speaker) so the next change counts as a change. */
    private fun settleOnSpeaker(rt: EqRuntime) {
        rt.onRouteChanged()
        waitFor(3_000, "the speaker to be recorded") { rt.controller.deviceRules().any { it.key == "speaker" } }
    }

    private fun collectMessages(holder: UiStateHolder): CopyOnWriteArrayList<UiMessage> {
        val got = CopyOnWriteArrayList<UiMessage>()
        scope.launch { holder.messages.collect { got.add(it) } }
        Thread.sleep(150)
        return got
    }

    @Test
    fun a_new_device_applies_its_preset_once_as_one_undo_step() {
        val rt = runtime()
        val holder = holderFor(rt)
        val key = seedBright(rt)
        rt.controller.setDevicePreset(buds.key, key)
        settleOnSpeaker(rt)
        val messages = collectMessages(holder)
        val serialBefore = holder.editSerial.value

        route.info = buds
        rt.onRouteChanged()
        waitFor(3_000, "the preset to apply") { rt.coreRevision.value == 1 }

        assertEquals(6f, gain0(rt), 0.001f)
        waitFor(3_000, "the notice") { messages.isNotEmpty() }
        val m = messages.single()
        assertEquals("Bright applied for Bluetooth: Buds", m.text)
        assertTrue(m.undoable)
        waitFor(3_000, "the screen to show the preset") { holder.ui.value.graphicGainsDb[0] == 6f }
        assertTrue("the edit serial moved", holder.editSerial.value > serialBefore)

        // The same route again changes nothing.
        rt.onRouteChanged()
        Thread.sleep(300)
        assertEquals(1, rt.coreRevision.value)

        // One undo brings the old sound back.
        holder.undoIfUnchanged(m.serial)
        assertEquals(-3f, gain0(rt), 0.001f)
        waitFor(3_000, "the screen to show the old sound") { holder.ui.value.graphicGainsDb[0] == -3f }
    }

    @Test
    fun an_older_applied_undo_cannot_undo_the_device_change() {
        val rt = runtime()
        val holder = holderFor(rt)
        val key = seedBright(rt)
        rt.controller.setDevicePreset(buds.key, key)
        settleOnSpeaker(rt)
        val serialBefore = holder.editSerial.value

        route.info = buds
        rt.onRouteChanged()
        waitFor(3_000, "the preset to apply") { rt.coreRevision.value == 1 }
        waitFor(3_000, "the serial to move") { holder.editSerial.value > serialBefore }

        holder.undoIfUnchanged(serialBefore)
        assertEquals("the stale Undo did nothing", 6f, gain0(rt), 0.001f)
    }

    @Test
    fun a_restart_on_the_same_device_applies_nothing() {
        val rt = runtime()
        val key = seedBright(rt)
        rt.controller.setDevicePreset(buds.key, key)
        route.info = buds
        rt.onRouteChanged()
        // A first sighting on a fresh state counts as a change, so the preset applies once here.
        waitFor(3_000, "the first apply") { rt.coreRevision.value == 1 }
        rt.controller.edit { it.setGraphicGain(0u, 2f) }
        rt.flush()
        rt.shutdown()

        val again = runtime()
        again.onRouteChanged()
        Thread.sleep(400)
        assertEquals(0, again.coreRevision.value)
        assertEquals("the manual edit survives the restart", 2f, gain0(again), 0.001f)
    }

    @Test
    fun a_device_without_a_rule_or_preset_changes_nothing_but_is_listed() {
        val rt = runtime()
        val key = seedBright(rt)
        settleOnSpeaker(rt)

        route.info = wired
        rt.onRouteChanged()
        waitFor(3_000, "the device to be listed") { rt.controller.deviceRules().any { it.key == "wired" } }
        assertEquals(-3f, gain0(rt), 0.001f)
        assertEquals(0, rt.coreRevision.value)

        // A rule that keeps the current sound behaves the same.
        rt.controller.setDevicePreset(buds.key, null)
        route.info = buds
        rt.onRouteChanged()
        waitFor(3_000, "the second device to be listed") { rt.controller.deviceRules().any { it.key == buds.key } }
        Thread.sleep(200)
        assertEquals(-3f, gain0(rt), 0.001f)
        assertEquals(0, rt.coreRevision.value)
        assertTrue(key.isNotEmpty())
    }

    @Test
    fun an_open_gesture_defers_the_preset_until_it_ends() {
        val rt = runtime()
        val holder = holderFor(rt)
        val key = seedBright(rt)
        rt.controller.setDevicePreset(buds.key, key)
        settleOnSpeaker(rt)
        val messages = collectMessages(holder)

        holder.onBandDragStart(0)
        holder.onBandDragChange(0, 2f)
        route.info = buds
        rt.onRouteChanged()
        Thread.sleep(400)
        assertEquals("nothing happens while the gesture is open", 0, rt.coreRevision.value)
        assertEquals(2f, gain0(rt), 0.001f)
        assertTrue(messages.isEmpty())

        holder.onBandDragChange(0, 2.5f)
        holder.onBandDragEnd()
        waitFor(3_000, "the deferred apply") { rt.coreRevision.value == 1 }
        Thread.sleep(300)
        assertEquals("applied exactly once", 1, rt.coreRevision.value)
        assertEquals(6f, gain0(rt), 0.001f)

        // One undo goes back to where the gesture ended, not further.
        val m = messages.single()
        holder.undoIfUnchanged(m.serial)
        assertEquals(2.5f, gain0(rt), 0.001f)
        assertTrue(rt.controller.snapshot().canUndo)
    }

    @Test
    fun a_deferred_preset_is_dropped_when_the_device_is_gone_before_the_gesture_ends() {
        val rt = runtime()
        val holder = holderFor(rt)
        val key = seedBright(rt)
        rt.controller.setDevicePreset(buds.key, key)
        settleOnSpeaker(rt)

        holder.onBandDragStart(0)
        holder.onBandDragChange(0, 2f)
        route.info = buds
        rt.onRouteChanged()
        Thread.sleep(400)
        // The buds are unplugged again before the finger lifts.
        route.info = RouteInfo.SPEAKER
        rt.onRouteChanged()
        Thread.sleep(400)
        holder.onBandDragEnd()
        Thread.sleep(600)

        assertEquals("the buds' preset must not land on the speaker", 0, rt.coreRevision.value)
        assertEquals(2f, gain0(rt), 0.001f)
    }

    @Test
    fun a_session_with_an_app_preset_is_left_alone() {
        val rt = runtime()
        rt.controller.edit { it.setGraphicGain(0u, 4f) }
        val mellow = rt.controller.savePreset("Mellow")
        val bright = seedBright(rt)
        rt.controller.setDevicePreset(buds.key, bright)
        rt.post(SessionIntent.Event.Open(61, "com.a"))
        rt.post(SessionIntent.Event.Open(62, "com.b"))
        waitFor(3_000, "both sessions") { factory.creates().size == 2 }
        assertEquals(null, rt.setAppPreset("com.a", mellow))
        settleOnSpeaker(rt)
        waitFor(3_000, "the app preset to reach its session") {
            factory.handle(61)!!.readBandGain(0) != factory.handle(62)!!.readBandGain(0)
        }
        val appBefore = factory.handle(61)!!.readBandGain(0)
        val liveBefore = factory.handle(62)!!.readBandGain(0)

        route.info = buds
        rt.onRouteChanged()
        waitFor(3_000, "the preset to apply") { rt.coreRevision.value == 1 }
        waitFor(3_000, "the live session to follow") { factory.handle(62)!!.readBandGain(0) != liveBefore }
        assertEquals(appBefore, factory.handle(61)!!.readBandGain(0), 0.0001f)
        assertNotEquals(liveBefore, factory.handle(62)!!.readBandGain(0))
    }
}

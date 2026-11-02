package com.dylenq.eq.ui

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.RuntimeRule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Import and export through [UiStateHolder], driven by real Uris to files in the app cache (the file
 * scheme goes through the same ContentResolver streams as a picked document). The system picker is not
 * driven here.
 */
class ImportExportHolderTest {
    @get:Rule val rt = RuntimeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver
    private val ctrl get() = rt.runtime.controller
    private lateinit var holder: UiStateHolder
    private lateinit var dir: File
    private val got = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before fun setUp() {
        holder = UiStateHolder(rt.runtime)
        dir = File(ctx.cacheDir, "io-${System.nanoTime()}").also { it.mkdirs() }
        val ready = CompletableDeferred<Unit>()
        scope.launch { holder.messages.onSubscription { ready.complete(Unit) }.collect { got.add(it) } }
        runBlocking { withTimeout(2000) { ready.await() } }
    }

    @After fun tearDown() {
        scope.cancel()
        holder.close()
        dir.deleteRecursively()
    }

    private fun file(name: String, text: String) = File(dir, name).also { it.writeText(text) }.let { Uri.fromFile(it) }

    private fun import(uri: Uri) = runBlocking { holder.importEqUri(resolver, uri) }

    private fun export(uri: Uri) = runBlocking { holder.exportEqUri(resolver, uri) }

    private fun lastMessage(): UiMessage {
        val end = System.currentTimeMillis() + 2000
        while (got.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        return got.last()
    }

    private data class State(val keys: List<String>, val active: String?, val mode: EqMode, val bands: List<Band>, val gains: List<Float>, val preamp: Float, val canUndo: Boolean)

    private fun state(): State {
        val s = ctrl.snapshot()
        return State(s.presets.map { it.key }, s.activePresetKey, s.mode, s.bands, s.graphicGainsDb, s.preampDb, holder.ui.value.canUndo)
    }

    private val parametric = """
        Preamp: -4.5 dB
        Filter 1: ON PK Fc 105 Hz Gain 3.5 dB Q 0.7
        Filter 2: ON LSC Fc 30 Hz Gain -2 dB Q 0.71
        Filter 3: OFF PK Fc 3000 Hz Gain 1.5 dB Q 2.2
    """.trimIndent()

    @Test fun parametricFileBecomesOneUndoStep() {
        holder.onBandDragStart(2)
        holder.onBandDragChange(2, 6f)
        holder.onBandDragEnd()
        val before = state()
        assertEquals(EqMode.GRAPHIC, before.mode)

        val r = import(file("HD650 ParametricEQ.txt", parametric))

        assertEquals(PresetResult.Ok, r)
        val after = ctrl.snapshot()
        assertEquals(EqMode.PARAMETRIC, after.mode)
        assertEquals(3, after.bands.size)
        assertEquals(-4.5f, after.preampDb, 0f)
        assertEquals("HD650", after.activePresetName)
        assertTrue(after.presets.any { it.name == "HD650" && !it.builtin })
        val m = lastMessage()
        assertEquals("Imported HD650, 3 filters", m.text)
        assertTrue(m.undoable)

        holder.undo()
        val undone = state()
        assertEquals(before.mode, undone.mode)
        assertEquals(before.gains, undone.gains)
        assertEquals(before.preamp, undone.preamp, 0f)
        assertEquals(before.active, undone.active)
        assertEquals(before.canUndo, undone.canUndo)
    }

    @Test fun skippedFiltersAreCounted() {
        import(file("Odd ParametricEQ.txt", parametric + "\nFilter 4: ON ZZ Fc 100 Hz Gain 1 dB Q 1\nFilter 5: ON PK Fc 100 Hz Gain 1 dB Q 1"))
        assertEquals("Imported Odd, 4 filters, 1 skipped", lastMessage().text)
    }

    @Test fun graphicFileFollowsTheBandCountInUse() {
        val curve = "GraphicEQ: 20 -2; 100 3; 1000 0; 20000 -4"
        for (n in listOf(10, 31)) {
            got.clear()
            holder.setBandCount(n)
            import(file("Sub $n GraphicEQ.txt", curve))
            val s = ctrl.snapshot()
            assertEquals(n.toUInt(), s.graphicBandCount)
            assertEquals(n, s.graphicGainsDb.size)
            assertEquals(EqMode.GRAPHIC, s.mode)
            assertEquals("Imported Sub $n, $n bands", lastMessage().text)
        }
    }

    @Test fun flatGraphicFileLeavesEveryGainAtZero() {
        holder.onBandDragStart(1)
        holder.onBandDragChange(1, 5f)
        holder.onBandDragEnd()
        import(file("Flat GraphicEQ.txt", "GraphicEQ: 20 0; 1000 0; 20000 0"))
        assertTrue(ctrl.snapshot().graphicGainsDb.all { it == 0f })
    }

    @Test fun bigValuesClampAndTheMessageSaysHowMany() {
        import(file("Loud GraphicEQ.txt", "GraphicEQ: 20 90; 20000 90"))
        val gains = ctrl.snapshot().graphicGainsDb
        assertTrue(gains.all { it == 24f })
        assertEquals("Imported Loud, ${gains.size} bands, ${gains.size} clamped", lastMessage().text)
    }

    @Test fun exportThenImportGivesAnEqualCurve() {
        import(file("Src ParametricEQ.txt", parametric))
        val first = ctrl.snapshot()
        val out = Uri.fromFile(File(dir, "out.txt"))

        lastMessage() // the import's own message arrives asynchronously; let it land before clearing
        got.clear()
        assertEquals(PresetResult.Ok, export(out))
        assertEquals("Exported Src", lastMessage().text)
        val text = File(dir, "out.txt").readText()
        assertTrue(text, text.startsWith("Preamp: "))

        import(Uri.fromFile(File(dir, "out.txt").copyTo(File(dir, "Copy ParametricEQ.txt"))))
        val second = ctrl.snapshot()
        assertEquals("Copy", second.activePresetName)
        assertEquals(first.bands, second.bands)
        assertEquals(first.preampDb, second.preampDb, 0f)
    }

    @Test fun nameClashImportsAsNumberedName() {
        import(file("Clash ParametricEQ.txt", parametric))
        import(file("Clash ParametricEQ.txt", parametric))
        val names = ctrl.snapshot().presets.map { it.name }
        assertTrue(names.contains("Clash"))
        assertTrue(names.contains("Clash 2"))
    }

    private fun assertRejectedAndUnchanged(uri: Uri, message: String) {
        got.clear()
        val before = state()
        val serial = holder.editSerial.value
        val r = import(uri)
        assertEquals(PresetResult.Rejected(message), r)
        assertEquals(message, lastMessage().text)
        assertEquals(before, state())
        assertEquals(serial, holder.editSerial.value)
    }

    @Test fun overSizeFileIsRejected() {
        val big = file("Big ParametricEQ.txt", "a".repeat(1_000_001))
        assertRejectedAndUnchanged(big, ImportExportCopy.TOO_LARGE)
    }

    @Test fun binaryFileIsRejected() {
        val f = File(dir, "bin.txt").also { it.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01)) }
        assertRejectedAndUnchanged(Uri.fromFile(f), ImportExportCopy.NOT_AN_EQ_FILE)
    }

    @Test fun nonEqTextIsRejected() {
        assertRejectedAndUnchanged(file("notes.txt", "Buy milk\nCall mum\n"), ImportExportCopy.NOT_AN_EQ_FILE)
        assertRejectedAndUnchanged(file("empty.txt", ""), ImportExportCopy.NOT_AN_EQ_FILE)
        assertRejectedAndUnchanged(file("amp ParametricEQ.txt", "Preamp: -3 dB"), ImportExportCopy.NOT_AN_EQ_FILE)
    }

    @Test fun missingFileIsRejected() {
        assertRejectedAndUnchanged(Uri.fromFile(File(dir, "gone.txt")), ImportExportCopy.UNREADABLE)
    }

    @Test fun fullPresetListIsRejected() {
        var n = 0
        while (holder.savePresetAs("Fill $n") == PresetResult.Ok && n < 200) n++
        assertTrue(n in 1..199)
        assertRejectedAndUnchanged(file("One More ParametricEQ.txt", parametric), PresetNames.TOO_MANY)
        assertFalse(ctrl.snapshot().presets.any { it.name == "One More" })
    }

    @Test fun bomAndCrlfFilesImport() {
        val f = File(dir, "Win ParametricEQ.txt").also {
            it.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + parametric.replace("\n", "\r\n").toByteArray())
        }
        assertEquals(PresetResult.Ok, import(Uri.fromFile(f)))
        assertEquals(3, ctrl.snapshot().bands.size)
    }
}

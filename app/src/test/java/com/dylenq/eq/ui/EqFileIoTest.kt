package com.dylenq.eq.ui

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqFileIoTest {
    private val line = "Preamp: -3.0 dB\r\nFilter 1: ON PK Fc 100 Hz Gain 2.0 dB Q 1.0\r\n"

    @Test fun plainUtf8Decodes() = assertEquals("Preamp: -3 dB\n", decodeEqText("Preamp: -3 dB\n".toByteArray()))

    @Test fun bomIsRemoved() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + line.toByteArray()
        assertEquals(line, decodeEqText(bytes))
    }

    @Test fun crlfKeepsItsMeaning() {
        val text = decodeEqText(line.toByteArray())!!
        assertEquals(listOf("Preamp: -3.0 dB", "Filter 1: ON PK Fc 100 Hz Gain 2.0 dB Q 1.0"), text.lines().filter { it.isNotBlank() }.map { it.trim() })
    }

    @Test fun nonAsciiTextDecodes() = assertEquals("Écouteurs 🎧", decodeEqText("Écouteurs 🎧".toByteArray()))

    @Test fun nulByteIsRejected() = assertNull(decodeEqText(byteArrayOf(0x50, 0x00, 0x51)))

    @Test fun invalidUtf8IsRejected() = assertNull(decodeEqText(byteArrayOf(0x50, 0xC3.toByte(), 0x28)))

    @Test fun emptyDecodesToEmpty() = assertEquals("", decodeEqText(ByteArray(0)))

    @Test fun exactlyTheLimitIsAccepted() {
        val data = ByteArray(1000) { 'a'.code.toByte() }
        assertArrayEquals(data, readBounded(ByteArrayInputStream(data), 1000))
    }

    @Test fun oneOverTheLimitIsRejected() {
        assertNull(readBounded(ByteArrayInputStream(ByteArray(1001)), 1000))
    }

    @Test fun readingStopsAtLimitPlusOne() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                served++
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
                served += len
                return len
            }
        }
        assertNull(readBounded(endless, 1000))
        assertTrue("read $served bytes", served <= 1001 + 8192)
    }

    @Test fun smallStreamReadsFully() = assertNotNull(readBounded(ByteArrayInputStream(ByteArray(5)), 1000))

    @Test fun nameStripsPathPieces() {
        assertEquals("HD650 ParametricEQ.txt", importNameFrom("Download/eq/HD650 ParametricEQ.txt"))
        assertEquals("a.txt", importNameFrom("C:\\files\\a.txt"))
    }

    @Test fun nullOrBlankNameBecomesImported() {
        assertEquals("Imported", importNameFrom(null))
        assertEquals("Imported", importNameFrom("  "))
        assertEquals("Imported", importNameFrom("dir/"))
    }
}

package com.dylenq.eq

import com.dylenq.eq.core.CurveData
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.core.Snapshot

/**
 * The only path through which app code mutates the [EqCore]. EqCore has no change listener, so
 * every mutation goes through [edit] (or [endEdit]), which signals the runtime worker to apply.
 */
class EqController internal constructor(
    private val core: EqCore,
    private val markDirty: () -> Unit,
) {
    /** Runs [block] on the caller's thread (EqCore is mutex-guarded), then asks the worker to apply. */
    fun <T> edit(block: (EqCore) -> T): T {
        try {
            return block(core)
        } finally {
            markDirty()
        }
    }

    fun setBypass(on: Boolean) = edit { it.setBypass(on) }

    /** Flips bypass and returns the new value. */
    fun toggleBypass(): Boolean = edit { c ->
        val on = !c.snapshot().bypass
        c.setBypass(on)
        on
    }

    /** Starts one undo step; pair with [endEdit]. */
    fun beginEdit() = core.beginEdit()

    fun endEdit() = edit { it.endEdit() }

    fun snapshot(): Snapshot = core.snapshot()

    fun engineParams(sampleRate: UInt, frameDurationMs: Float): EngineParams =
        core.engineParams(sampleRate, frameDurationMs)

    /** Read-only passthrough: computing a curve does not mutate the core, so it never marks dirty. */
    fun responseCurve(points: UInt, sampleRate: UInt, frameDurationMs: Float): CurveData =
        core.responseCurve(points, sampleRate, frameDurationMs)

    /** Read-only: one band's own response (dB) on the response-curve grid; never marks dirty. */
    fun bandResponse(index: UInt, points: UInt, sampleRate: UInt): FloatArray =
        core.bandResponseDb(index, points, sampleRate).toFloatArray()

    /** Restores the default bands and 0 dB preamp as one undo step. */
    fun resetBands() = edit { it.resetBands() }
}

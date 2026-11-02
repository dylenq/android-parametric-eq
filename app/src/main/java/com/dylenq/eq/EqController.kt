package com.dylenq.eq

import com.dylenq.eq.core.CurveData
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.core.AppRuleInfo
import com.dylenq.eq.core.DeviceRuleInfo
import com.dylenq.eq.core.Snapshot

/**
 * The only path through which app code mutates the [EqCore]. EqCore has no change listener, so
 * every mutation goes through [edit] (or [endEdit]), which signals the runtime worker to apply.
 */
class EqController internal constructor(
    private val core: EqCore,
    private val markDirty: () -> Unit,
) {
    /** Called after a preset was saved, renamed or deleted, so cached preset params are dropped. */
    internal var onPresetsChanged: () -> Unit = {}

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

    /** The Rust core's version string (About row). */
    fun coreVersion(): String = core.version()

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

    /** Read-only: the params applying preset [key] would give; throws when the key does not resolve. */
    fun engineParamsForPreset(key: String, sampleRate: UInt, frameDurationMs: Float): EngineParams =
        core.engineParamsForPreset(key, sampleRate, frameDurationMs)

    /** Preset changes go through here so the runtime can drop what it cached for the old preset. */
    fun savePreset(name: String): String = presetEdit { it.savePreset(name) }

    fun renamePreset(key: String, newName: String): String = presetEdit { it.renamePreset(key, newName) }

    fun deletePreset(key: String) = presetEdit { it.deletePreset(key) }

    private fun <T> presetEdit(block: (EqCore) -> T): T = edit {
        try {
            block(it)
        } finally {
            onPresetsChanged()
        }
    }

    /** Read-only: every per-app rule. */
    fun appRules(): List<AppRuleInfo> = core.appRules()

    /** Records that an app has a session. Not an edit: the caller saves without an apply. */
    fun noteApp(pkg: String): Boolean = core.noteApp(pkg)

    fun setAppEnabled(pkg: String, on: Boolean) = edit { it.setAppEnabled(pkg, on) }

    /** Null returns the app to the live EQ; a key that does not resolve throws. */
    fun setAppPreset(pkg: String, key: String?) = edit { it.setAppPreset(pkg, key) }

    /** Restores the default bands and 0 dB preamp as one undo step. */
    fun resetBands() = edit { it.resetBands() }

    /** Read-only: every per-device rule. */
    fun deviceRules(): List<DeviceRuleInfo> = core.deviceRules()

    /**
     * Records the output now in use and returns the preset to apply, if the device changed and has one.
     * Not an edit: the caller saves through the debounce and applies the preset itself.
     */
    fun recordDevice(key: String, name: String): String? = core.recordDevice(key, name)

    /** Null keeps the current sound on that device; a key that does not resolve throws. */
    fun setDevicePreset(key: String, presetKey: String?) = edit { it.setDevicePreset(key, presetKey) }

    /** Applies preset [key] as one undo step. True when the core recorded a step. */
    fun applyPreset(key: String): Boolean = edit { it.applyPreset(key) }
}

package com.dylenq.eq.ui

import com.dylenq.eq.service.DeviceEntry

/** Pure copy and ordering rules for the Settings "Output devices" section. */
object DevicesCopy {
    const val NO_PRESET = "None (keep current sound)"
    const val EMPTY_TITLE = "No devices yet"
    const val EMPTY_BODY = "The list fills as you switch between speakers and headphones."
    const val NOTE = "Devices with no preset keep the current sound. A preset is applied when you switch to that device."
    const val CURRENT = "In use now"

    fun countText(n: Int): String = if (n == 1) "1 device" else "$n devices"

    /** The device in use first, then most recently seen first, ties by name. */
    fun sorted(devices: List<DeviceEntry>): List<DeviceEntry> =
        devices.sortedWith(
            compareByDescending<DeviceEntry> { it.current }
                .thenByDescending { it.lastSeen }
                .thenBy { it.name.lowercase() }
                .thenBy { it.key },
        )

    /** The picker text for a row. A missing key, or one that no longer resolves in [names], reads as no preset. */
    fun presetText(key: String?, names: Map<String, String>): String =
        if (key == null) NO_PRESET else names[key] ?: NO_PRESET
}

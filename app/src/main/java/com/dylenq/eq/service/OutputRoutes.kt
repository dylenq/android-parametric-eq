package com.dylenq.eq.service

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo

/**
 * Fires [onRouteChanged] when an output device appears or goes away. A new output can change the
 * sample rate the curve is fitted to; per-device presets will hook in here.
 */
class OutputRouteCallback(private val onRouteChanged: () -> Unit) : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onRouteChanged()
    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onRouteChanged()
}

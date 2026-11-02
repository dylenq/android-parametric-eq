package com.dylenq.eq.service

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Fires [onRouteChanged] when an output device appears or goes away. A new output can change the
 * sample rate the curve is fitted to, and it is where per-device presets hook in.
 */
class OutputRouteCallback(private val onRouteChanged: () -> Unit) : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onRouteChanged()
    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onRouteChanged()
}

/** The output the audio is going to: a stable [key] for the rule and a [name] for the screen. Never an address. */
data class RouteInfo(val key: String, val name: String) {
    companion object {
        val SPEAKER = RouteInfo("speaker", "Phone speaker")
    }
}

/** Where the runtime asks which output is in use. Behind an interface so tests can switch it. */
interface OutputRoute {
    fun current(): RouteInfo
}

/** One output as the audio system reports it: the type constant and the product name, nothing else. */
data class RouteCandidate(val type: Int, val product: String?)

private const val MAX_ROUTE_NAME_CHARS = 40

private enum class RouteKind { USB, WIRED, BLUETOOTH, SPEAKER, OTHER }

private fun kindOf(type: Int): RouteKind = when (type) {
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> RouteKind.USB
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> RouteKind.WIRED
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID -> RouteKind.BLUETOOTH
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteKind.SPEAKER
    else -> RouteKind.OTHER
}

// Product names come from the remote hardware: plain text only, no control characters, capped.
private fun cleanProduct(product: String?): String =
    (product ?: "").filter { !it.isISOControl() }.trim().take(MAX_ROUTE_NAME_CHARS).trim()

/** Pure mapping from the type constant and product name to the rule key and display name (D-07). */
fun routeInfoFor(type: Int, product: String?): RouteInfo {
    val p = cleanProduct(product)
    return when (kindOf(type)) {
        RouteKind.SPEAKER -> RouteInfo.SPEAKER
        RouteKind.WIRED -> RouteInfo("wired", "Wired headphones")
        RouteKind.BLUETOOTH ->
            if (p.isEmpty()) RouteInfo("bluetooth", "Bluetooth") else RouteInfo("bluetooth:$p", "Bluetooth: $p")
        RouteKind.USB ->
            if (p.isEmpty()) RouteInfo("usb", "USB") else RouteInfo("usb:$p", "USB: $p")
        RouteKind.OTHER ->
            if (p.isEmpty()) RouteInfo("other:$type", "Other output") else RouteInfo("other:$type:$p", p)
    }
}

/** The output to treat as current among [candidates]: USB, wired, Bluetooth, then speaker (D-06). */
fun pickRoute(candidates: List<RouteCandidate>): RouteInfo {
    val best = candidates.minByOrNull { kindOf(it.type).ordinal } ?: return RouteInfo.SPEAKER
    return routeInfoFor(best.type, best.product)
}

/**
 * Reads the output from [AudioManager] with no permission: on API 33+ the device the media attributes
 * route to, otherwise (or when that fails or is empty) the best of the connected outputs.
 * The address is never read and BLUETOOTH_CONNECT is not needed.
 */
class AudioManagerRoute(private val audio: AudioManager) : OutputRoute {
    /** Which path answered last, and whether the first one threw a SecurityException. For the emulator probe. */
    @Volatile var lastPath: String = "none"
        private set
    @Volatile var sawSecurityException: Boolean = false
        private set

    override fun current(): RouteInfo {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val first = audio.getAudioDevicesForAttributes(attrs).firstOrNull()
                if (first != null) {
                    lastPath = "getAudioDevicesForAttributes"
                    return routeInfoFor(first.type, first.productName?.toString())
                }
            } catch (e: SecurityException) {
                sawSecurityException = true
                Log.w("EQ-SVC", "getAudioDevicesForAttributes refused, using getDevices")
            } catch (t: Throwable) {
                Log.w("EQ-SVC", "getAudioDevicesForAttributes failed: ${t.javaClass.name}")
            }
        }
        lastPath = "getDevices"
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return pickRoute(outputs.map { RouteCandidate(it.type, it.productName?.toString()) })
    }
}

/**
 * Trailing debounce (D-08): each [trigger] cancels the pending run and schedules a new one, so a burst of
 * route events becomes one call after [delayMs]. [schedule] returns the way to cancel what it scheduled;
 * tests pass a fake, so they need no sleeps.
 */
class Debouncer(
    private val delayMs: Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val action: () -> Unit,
) {
    private var cancelPending: (() -> Unit)? = null

    @Synchronized
    fun trigger() {
        cancelPending?.invoke()
        cancelPending = schedule(delayMs, ::fire)
    }

    private fun fire() {
        synchronized(this) { cancelPending = null }
        action()
    }

    @Synchronized
    fun cancel() {
        cancelPending?.invoke()
        cancelPending = null
    }

    companion object {
        /** Route events settle in steps, so wait this long for the last one. */
        const val ROUTE_DEBOUNCE_MS = 300L
    }
}

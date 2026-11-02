package com.dylenq.eq.tile

import com.dylenq.eq.service.ServiceStatus

/** What the Quick Settings tile shows. Pure, so the JVM can test it. */
data class TileModel(val state: State, val label: String, val subtitle: String) {
    enum class State { ACTIVE, INACTIVE, UNAVAILABLE }

    /** A tap does nothing while the tile is unavailable. */
    val canToggle: Boolean get() = state != State.UNAVAILABLE

    companion object {
        const val LABEL = "EQ"
        const val ON = "On"
        const val BYPASSED = "Bypassed"
        const val UNAVAILABLE = "Unavailable"

        /** Shown when the core could not be loaded and there is no status to read. */
        val Unavailable = TileModel(State.UNAVAILABLE, LABEL, UNAVAILABLE)

        fun from(status: ServiceStatus): TileModel = when {
            status.unavailable -> Unavailable
            status.bypass -> TileModel(State.INACTIVE, LABEL, BYPASSED)
            else -> TileModel(State.ACTIVE, LABEL, ON)
        }
    }
}

package com.dylenq.eq.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.dylenq.eq.EqApp
import com.dylenq.eq.EqRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: active while EQ is on, inactive while bypassed. A tap flips bypass through the
 * shared runtime, so it works with the foreground service not running; it never starts the service.
 */
class EqTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var collecting: Job? = null

    // Null when the core cannot be loaded (LinkageError and friends); the tile then shows unavailable.
    private fun runtimeOrNull(): EqRuntime? = try {
        (application as EqApp).runtime()
    } catch (t: Throwable) {
        Log.w("EQ-TILE", "runtime unavailable: ${t.javaClass.name}: ${t.message}")
        null
    }

    override fun onStartListening() {
        super.onStartListening()
        val rt = runtimeOrNull()
        if (rt == null) {
            render(TileModel.Unavailable)
            return
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        collecting = s.launch { rt.status.collect { render(TileModel.from(it)) } }
    }

    override fun onStopListening() {
        collecting?.cancel()
        collecting = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val rt = runtimeOrNull()
        if (rt == null) {
            render(TileModel.Unavailable)
            return
        }
        try {
            // The controller's edit marks the runtime dirty, which applies and saves the new state.
            rt.controller.toggleBypass()
        } catch (t: Throwable) {
            Log.w("EQ-TILE", "toggle failed: ${t.javaClass.name}: ${t.message}")
            render(TileModel.Unavailable)
        }
    }

    private fun render(m: TileModel) {
        val tile = qsTile ?: return
        tile.state = when (m.state) {
            TileModel.State.ACTIVE -> Tile.STATE_ACTIVE
            TileModel.State.INACTIVE -> Tile.STATE_INACTIVE
            TileModel.State.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.label = m.label
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = m.subtitle
        tile.updateTile()
    }
}

package com.dylenq.playerstub

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Its launcher entry is what lets the tracker keep this app as a real player. A shell script can also
 * pass `--es play start|stop` to start or stop the silent PlayService, because a background app may
 * not start a service itself.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.getStringExtra("play")) {
            "start" -> startService(Intent(this, PlayService::class.java).setAction(PlayService.ACTION_PLAY))
            "stop" -> startService(Intent(this, PlayService::class.java).setAction(PlayService.ACTION_STOP))
        }
        finish()
    }
}

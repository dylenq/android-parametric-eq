package com.dylenq.eq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylenq.eq.service.EqService
import com.dylenq.eq.ui.CurveCanvas

/** Host activity: asks for notifications, starts the foreground service, shows the Compose UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, EqService::class.java))
        val holder = (application as EqApp).uiHolder()
        setContent {
            MaterialTheme {
                val ui by holder.ui.collectAsStateWithLifecycle()
                Column(Modifier.systemBarsPadding()) {
                    // Draw-phase read: curve updates redraw without recomposing.
                    CurveCanvas({ ui.curve }, Modifier.fillMaxWidth().height(200.dp))
                }
            }
        }
    }
}

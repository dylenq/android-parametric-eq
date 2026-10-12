package com.dylenq.eq

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.dylenq.eq.service.EqService
import com.dylenq.eq.ui.EqScreen
import com.dylenq.eq.ui.EqTheme

/**
 * Host activity: starts the foreground service and shows the Compose UI. The notification permission is
 * requested only from the rationale banner's "Allow" (never at launch).
 */
class MainActivity : ComponentActivity() {
    /** Re-read in onResume; below API 33 there is no runtime permission, so it counts as granted. */
    private val notificationsGranted = mutableStateOf(true)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermission()
        startForegroundService(Intent(this, EqService::class.java))
        val app = application as EqApp
        val holder = app.uiHolder()
        val prefs = app.uiPrefs()
        enableEdgeToEdge()
        setContent {
            EqTheme {
                EqScreen(
                    holder = holder,
                    prefs = prefs,
                    notificationsGranted = notificationsGranted.value,
                    onStartService = ::startService,
                    onAllowNotifications = ::requestNotifications,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    private fun refreshPermission() {
        notificationsGranted.value = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun startService() {
        startForegroundService(Intent(this, EqService::class.java))
    }

    /** Only the rationale banner's "Allow" gets here. */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** App notification settings for this package only. */
    private fun openNotificationSettings() {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
        }
    }
}

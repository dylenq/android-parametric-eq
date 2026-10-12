package com.dylenq.eq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylenq.eq.service.EqService
import com.dylenq.eq.ui.BandCountMenu
import com.dylenq.eq.ui.CurveCard
import com.dylenq.eq.ui.GraphicSliderPanel

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
            // Interim theme until EqTheme (plan 04-06): dynamic colour on Android 12+, light/dark by system.
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    val ui by holder.ui.collectAsStateWithLifecycle()
                    // Interim layout until EqScreen (plan 04-06). Lambdas defer the state read, so a drag
                    // redraws the plot and sliders without recomposing the screen.
                    Column(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CurveCard({ ui }, holder::applySuggestedPreamp)
                        BandCountMenu(ui.graphicBandCount, holder::setBandCount)
                        GraphicSliderPanel(
                            state = { ui },
                            onDragStart = holder::onBandDragStart,
                            onChange = holder::onBandDragChange,
                            onDragEnd = holder::onBandDragEnd,
                            onReset = holder::resetBand,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

package com.dylenq.eq.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylenq.eq.core.EqMode

/** UI-SPEC: `clamp(30% of window height, 168, 260)` dp. */
fun defaultPlotHeight(screenHeightDp: Int): Float = (screenHeightDp * 0.30f).coerceIn(168f, 260f)

/** UI-SPEC "Horizontal screen margin": 12 dp below 360 dp, 24 dp from 600 dp, else 16 dp. */
fun screenMarginDp(widthDp: Int): Int = when {
    widthDp < 360 -> 12
    widthDp >= 600 -> 24
    else -> 16
}

private const val CONTENT_MIN_DP = 232f
private const val GAP_DP = 16f
private const val MAX_CONTENT_WIDTH_DP = 640

/**
 * Curve plot height for the column. The default is [defaultPlotHeight]; when an estimate of the sum of
 * the region minimums at that height no longer fits [bodyHeightDp], the column will scroll and the plot
 * is clamped to its 168 dp minimum. The estimate scales the text-bearing regions with the font scale
 * (capped at 2); it only decides this clamp, the layout itself measures real heights.
 */
fun plotHeightFor(screenHeightDp: Int, bodyHeightDp: Float, fontScale: Float, stacked: Boolean): Float {
    val plot = defaultPlotHeight(screenHeightDp)
    val fs = fontScale.coerceIn(1f, 2f)
    val curve = plot + 72f * fs
    val mode = if (stacked) 104f else 48f
    val preamp = 152f * fs
    val sum = curve + mode + CONTENT_MIN_DP + preamp + 3 * GAP_DP
    return if (sum > bodyHeightDp) 168f else plot
}

/**
 * The portrait single-column screen (UI-SPEC layout A). Top bar, strip and banner stay pinned; the curve,
 * mode row, mode content and preamp card scroll as one column only when their minimums do not fit.
 * [parametricContent] is the Phase 5 slot (the band list replaces the placeholder there).
 */
@Composable
fun EqScreen(
    holder: UiStateHolder,
    prefs: UiPrefs,
    notificationsGranted: Boolean,
    onStartService: () -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    parametricContent: @Composable (EqUiState) -> Unit = { ParametricPlaceholder(it.hasEnabledParametricBands) },
) {
    val ui by holder.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resetAll = rememberResetAll(holder, snackbar)
    EqErrorSnackbars(holder, snackbar)
    val reduced = rememberReducedMotion()
    val config = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val stacked = rememberModeRowStacked()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // Phase 5 slot: Undo/Redo icon buttons go in EqTopBar's phase5Slot.
            EqTopBar({ ui }, holder::setBypass, resetAll)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val margin = screenMarginDp(config.screenWidthDp).dp
            val bodyHeight = maxHeight
            Column(
                Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH_DP.dp)
                    .fillMaxSize()
                    .padding(horizontal = margin),
            ) {
                StatusStrip({ ui }, onStart = onStartService)
                BannerSlot(
                    notice = ui.notice,
                    permissionGranted = notificationsGranted,
                    prefs = prefs,
                    onDismissNotice = holder::dismissNotice,
                    onAllow = onAllowNotifications,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                )
                val plot = plotHeightFor(config.screenHeightDp, bodyHeight.value, fontScale, stacked).dp
                val scroll = rememberScrollState()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val avail = maxHeight
                    SingleColumn(
                        availableHeight = avail,
                        modifier = Modifier.verticalScroll(scroll).testTag("eq-column"),
                        curve = { CurveCard({ ui }, holder::applySuggestedPreamp, plotHeightOverride = plot) },
                        mode = { ModeRow(ui.mode, ui.graphicBandCount, holder::setMode, holder::setBandCount, stacked = stacked) },
                        content = {
                            // Phase 5 slot: ParametricBandList is `parametricContent`.
                            Crossfade(
                                targetState = ui.mode,
                                animationSpec = if (reduced) snap() else tween(200),
                                label = "mode",
                                modifier = Modifier.fillMaxSize(),
                            ) { m ->
                                if (m == EqMode.GRAPHIC) {
                                    GraphicSliderPanel(
                                        state = { ui },
                                        onDragStart = holder::onBandDragStart,
                                        onChange = holder::onBandDragChange,
                                        onDragEnd = holder::onBandDragEnd,
                                        onReset = holder::resetBand,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { parametricContent(ui) }
                                }
                            }
                        },
                        preamp = {
                            PreampCard(
                                state = { ui },
                                onPreamp = holder::setPreamp,
                                onDragStart = holder::preampDragStart,
                                onDragEnd = holder::preampDragEnd,
                                onReset = holder::resetPreamp,
                                onApplySuggested = holder::applySuggestedPreamp,
                                onLimiter = holder::setLimiter,
                            )
                        },
                    )
                }
                // Bottom padding; the navigation-bar inset is already in the Scaffold padding.
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Stacks [curve], [mode], [content] and [preamp] with 16 dp gaps. [content] gets whatever is left of
 * [availableHeight] but never less than 232 dp; when the four do not fit, the layout is taller than
 * [availableHeight] and the caller's `verticalScroll` takes over.
 */
@Composable
private fun SingleColumn(
    availableHeight: Dp,
    modifier: Modifier,
    curve: @Composable () -> Unit,
    mode: @Composable () -> Unit,
    content: @Composable () -> Unit,
    preamp: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { curve() }
            Box { mode() }
            Box { content() }
            Box { preamp() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val loose = androidx.compose.ui.unit.Constraints(minWidth = w, maxWidth = w)
        val gap = GAP_DP.dp.roundToPx()
        val avail = availableHeight.roundToPx()
        val curveP = measurables[0].measure(loose)
        val modeP = measurables[1].measure(loose)
        val preampP = measurables[3].measure(loose)
        val fixed = curveP.height + modeP.height + preampP.height + 3 * gap
        val min = CONTENT_MIN_DP.dp.roundToPx()
        val contentH = maxOf(min, avail - fixed)
        val contentP = measurables[2].measure(
            androidx.compose.ui.unit.Constraints(minWidth = w, maxWidth = w, minHeight = contentH, maxHeight = contentH),
        )
        val total = fixed + contentH
        layout(w, total) {
            var y = 0
            curveP.place(0, y); y += curveP.height + gap
            modeP.place(0, y); y += modeP.height + gap
            contentP.place(0, y); y += contentP.height + gap
            preampP.place(0, y)
        }
    }
}

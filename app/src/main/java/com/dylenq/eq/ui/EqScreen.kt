package com.dylenq.eq.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylenq.eq.core.EqMode

private const val MAX_PORTRAIT_WIDTH_DP = 640
private const val PANE_GAP_DP = 8f
private const val LEFT_PANE_FRACTION = 0.44f
private const val LEFT_PANE_MIN_DP = 280
private const val LEFT_PANE_PLOT_MIN_DP = 120f

/**
 * The screen (UI-SPEC layouts A and B). Portrait: top bar, strip and banner pinned, the curve, mode row,
 * mode content and preamp card as one column that scrolls only when the minimums do not fit. Landscape or
 * >= 840 dp: two panes (curve and mode row left, sliders and compact preamp card right), each scrolling
 * vertically only when its minimums do not fit. [spec] defaults to the real window.
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
    onOpenSettings: () -> Unit = {},
    spec: WindowSpec = WindowSpec.current(),
    parametricContent: @Composable (EqUiState) -> Unit = { ParametricPlaceholder(it.hasEnabledParametricBands) },
) {
    val ui by holder.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resetAll = rememberResetAll(holder, snackbar)
    EqErrorSnackbars(holder, snackbar)
    EqMessageSnackbars(holder, snackbar)
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    val reduced = rememberReducedMotion()
    val sliderScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    val parametricActions = remember(holder) {
        ParametricActions(
            onSelect = holder::selectBand,
            onDragStart = holder::parametricGestureStart,
            onNodeChange = holder::parametricNodeChange,
            onDragEnd = holder::parametricGestureEnd,
            onAddBand = holder::addBandAt,
            onQChange = holder::setBandQ,
            onDeselect = { holder.selectBand(null) },
            onSetKind = holder::setBandKind,
            onSetEnabled = holder::setBandEnabled,
            onDeleteBand = holder::removeBand,
            onNudgeFrequency = holder::nudgeBandFrequency,
            onNudgeGain = holder::nudgeBandGain,
            onScaleQ = holder::scaleBandQ,
            onAddBandFromList = holder::addBandFromList,
        )
    }

    val strip: @Composable () -> Unit = { StatusStrip({ ui }, onStart = onStartService) }
    val banner: @Composable () -> Unit = {
        BannerSlot(
            notice = ui.notice,
            permissionGranted = notificationsGranted,
            prefs = prefs,
            onDismissNotice = holder::dismissNotice,
            onAllow = onAllowNotifications,
            onOpenNotificationSettings = onOpenNotificationSettings,
        )
    }
    val mode: @Composable () -> Unit = {
        ModeRow(ui.mode, ui.graphicBandCount, holder::setMode, holder::setBandCount, stacked = spec.stackedModeRow)
    }
    val modeContent: @Composable (Boolean) -> Unit = { compact ->
        val tight = spec.tight && !compact
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
                    compact = compact,
                    tight = tight,
                    scroll = sliderScroll,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { parametricContent(ui) }
            }
        }
    }
    val preamp: @Composable (Boolean) -> Unit = { compact ->
        PreampCard(
            state = { ui },
            onPreamp = holder::setPreamp,
            onDragStart = holder::preampDragStart,
            onDragEnd = holder::preampDragEnd,
            onReset = holder::resetPreamp,
            onApplySuggested = holder::applySuggestedPreamp,
            onLimiter = holder::setLimiter,
            compact = compact,
            tight = spec.tight && !compact,
        )
    }

    Scaffold(
        // Focusable root so the shortcuts work with a hardware keyboard before any control has been focused.
        modifier = modifier
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e -> undoRedoShortcut(e, holder) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            EqTopBar(
                { ui }, holder::setBypass, resetAll, heightDp = spec.topBarHeightDp,
                onUndo = holder::undo, onRedo = holder::redo, onOpenSettings = onOpenSettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val margin = spec.marginDp.dp
        if (!spec.twoPane) {
            BoxWithConstraints(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = MAX_PORTRAIT_WIDTH_DP.dp)
                        .fillMaxSize()
                        .padding(horizontal = margin),
                ) {
                    strip()
                    banner()
                    val scroll = rememberScrollState()
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        SingleColumn(
                            availablePx = constraints.maxHeight,
                            desiredPlotDp = defaultPlotHeight(spec.heightDp),
                            chromeDp = curveChromeDp(spec.fontScale, spec.widthDp),
                            contentMinDp = graphicPanelMinDp(spec.fontScale, compact = false, tight = spec.tight),
                            plotMinDp = if (spec.tight) PLOT_MIN_TIGHT_DP else PLOT_MIN_DP,
                            modifier = Modifier.verticalScroll(scroll).testTag("eq-column"),
                            curve = { CurveCard({ ui }, holder::applySuggestedPreamp, fillHeight = true, footerHeight = curveFooterHeight(spec.fontScale, spec.widthDp), parametric = parametricActions) },
                            mode = mode,
                            content = { modeContent(false) },
                            preamp = { preamp(false) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            Row(Modifier.padding(pad).fillMaxSize().padding(horizontal = margin).testTag("two-pane")) {
                BoxWithConstraints(Modifier.weight(LEFT_PANE_FRACTION).widthIn(min = LEFT_PANE_MIN_DP.dp).fillMaxHeight().testTag("left-pane")) {
                    FlexColumn(
                        availableHeight = maxHeight,
                        flexIndex = 2,
                        flexMinDp = curveChromeDp(spec.fontScale, spec.widthDp) + LEFT_PANE_PLOT_MIN_DP,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        slots = listOf(
                            strip,
                            banner,
                            { CurveCard({ ui }, holder::applySuggestedPreamp, fillHeight = true, footerHeight = curveFooterHeight(spec.fontScale, spec.widthDp), parametric = parametricActions) },
                            mode,
                        ),
                    )
                }
                Spacer(Modifier.width(16.dp))
                BoxWithConstraints(Modifier.weight(1f - LEFT_PANE_FRACTION).fillMaxHeight().testTag("right-pane")) {
                    FlexColumn(
                        availableHeight = maxHeight,
                        flexIndex = 0,
                        flexMinDp = graphicPanelMinDp(spec.fontScale, compact = true),
                        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("right-pane-scroll"),
                        slots = listOf({ modeContent(true) }, { preamp(true) }),
                    )
                }
            }
        }
    }
}

/**
 * Stacks the four portrait regions with 12 dp gaps. The curve plot and the slider panel share what the
 * mode row and preamp card leave ([planColumn]); when even the minimums do not fit [availableHeight],
 * the layout is taller than it and the caller's `verticalScroll` takes over.
 */
@Composable
private fun SingleColumn(
    availablePx: Int,
    desiredPlotDp: Float,
    chromeDp: Float,
    contentMinDp: Float,
    plotMinDp: Float,
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
        val loose = Constraints(minWidth = w, maxWidth = w)
        val d = density
        val modeP = measurables[1].measure(loose)
        val preampP = measurables[3].measure(loose)
        val fixedDp = (modeP.height + preampP.height) / d + chromeDp
        val plan = planColumn(availablePx / d, fixedDp, desiredPlotDp, contentMinDp = contentMinDp, plotMinDp = plotMinDp)
        val gap = (COLUMN_GAP_DP * d).roundToInt()
        val minContentPx = (contentMinDp * d).roundToInt()
        var curveH = ((chromeDp + plan.plotDp) * d).roundToInt()
        // Whole pixels: when it all fits, rounding must not push the panel under its minimum (that would scroll).
        if (!plan.scrolls) curveH = minOf(curveH, availablePx - modeP.height - preampP.height - 3 * gap - minContentPx)
        // In whole pixels: when everything fits, the panel takes exactly what is left (no rounding overflow).
        val contentH = if (plan.scrolls) {
            minContentPx
        } else {
            maxOf(minContentPx, availablePx - curveH - modeP.height - preampP.height - 3 * gap)
        }
        val curveP = measurables[0].measure(Constraints(w, w, curveH, curveH))
        val contentP = measurables[2].measure(Constraints(w, w, contentH, contentH))
        val total = curveP.height + modeP.height + contentP.height + preampP.height + 3 * gap
        layout(w, total) {
            var y = 0
            curveP.place(0, y); y += curveP.height + gap
            modeP.place(0, y); y += modeP.height + gap
            contentP.place(0, y); y += contentP.height + gap
            preampP.place(0, y)
        }
    }
}

/**
 * Vertical stack of [slots] with 8 dp gaps between non-empty ones; the slot at [flexIndex] gets whatever
 * is left of [availableHeight] but never less than [flexMinDp] (the layout is then taller and the
 * caller's `verticalScroll` takes over).
 */
@Composable
private fun FlexColumn(
    availableHeight: Dp,
    flexIndex: Int,
    flexMinDp: Float,
    modifier: Modifier,
    slots: List<@Composable () -> Unit>,
) {
    Layout(
        content = { slots.forEach { slot -> Box { slot() } } },
        modifier = modifier,
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val loose = Constraints(minWidth = w, maxWidth = w)
        val gap = (PANE_GAP_DP * density).roundToInt()
        val avail = availableHeight.roundToPx()
        val fixed = measurables.mapIndexed { i, m -> if (i == flexIndex) null else m.measure(loose) }
        val nonEmpty = measurables.indices.count { i -> i == flexIndex || fixed[i]!!.height > 0 }
        val used = fixed.filterNotNull().sumOf { it.height } + gap * (nonEmpty - 1)
        val flexH = maxOf((flexMinDp * density).roundToInt(), avail - used)
        val flexP = measurables[flexIndex].measure(Constraints(w, w, flexH, flexH))
        val placed = measurables.indices.map { i -> if (i == flexIndex) flexP else fixed[i]!! }
        val total = placed.sumOf { it.height } + gap * (nonEmpty - 1)
        layout(w, total) {
            var y = 0
            for (p in placed) {
                if (p.height == 0) continue
                p.place(0, y)
                y += p.height + gap
            }
        }
    }
}

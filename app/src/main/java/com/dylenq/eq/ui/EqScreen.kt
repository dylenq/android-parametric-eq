package com.dylenq.eq.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
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

/** True while the software keyboard is showing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberImeVisible(): Boolean = WindowInsets.isImeVisible

/** Parametric two-pane rules apply below this window height (UI-SPEC "Landscape"). */
private const val LANDSCAPE_PEQ_MAX_HEIGHT_DP = 480
private const val PEQ_TWO_PANE_PLOT_MIN_DP = 160f
private const val PEQ_TWO_PANE_PLOT_MAX_DP = 280f
private const val PEQ_LANDSCAPE_PLOT_MIN_DP = 140f
private val PEQ_LANDSCAPE_HEADER = 32.dp

/** The curve header height in the landscape Parametric form (the 32 dp override is ignored at large font). */
private fun landscapeHeaderDp(fontScale: Float): Float = if (fontScale >= 1.3f) curveHeaderHeight(fontScale).value else PEQ_LANDSCAPE_HEADER.value


/**
 * The screen (UI-SPEC layouts A and B). Portrait: top bar, strip and banner pinned, the curve, mode row,
 * mode content and preamp card as one column that scrolls only when the minimums do not fit. Landscape or
 * >= 840 dp: two panes (curve and mode row left, sliders and compact preamp card right), each scrolling
 * vertically only when its minimums do not fit. [spec] defaults to the real window.
 * In Parametric mode the mode slot is the band list ([ParametricListRegion]) with the preamp card as its last
 * item; the portrait column pins the curve card and mode row and the list scrolls. With the keyboard up
 * ([imeVisible]) the curve card shrinks to a 120 dp plot and the strip, banner and mode row are not composed.
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
    imeVisible: Boolean = rememberImeVisible(),
) {
    val ui by holder.ui.collectAsStateWithLifecycle()
    val parametric = ui.mode == EqMode.PARAMETRIC
    val imeCompact = parametric && imeVisible
    // Landscape or wide window under 480 dp tall: the tighter Parametric two-pane form.
    val landscapePeq = parametric && spec.twoPane && spec.heightDp < LANDSCAPE_PEQ_MAX_HEIGHT_DP
    val focus = LocalFocusManager.current
    val pinchSeen = remember { mutableStateOf(prefs.pinchHintSeen) }
    val bandActions = remember(holder) { holder.bandListActions() }
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
            onPinchCompleted = {
                prefs.pinchHintSeen = true
                pinchSeen.value = true
            },
        )
    }

    // With the IME open (Parametric) the strip, banner and mode row are not composed; their state lives in
    // the holder and the prefs, so they come back unchanged.
    val strip: @Composable () -> Unit = {
        if (!imeCompact) StatusStrip({ ui }, onStart = onStartService, showHint = !landscapePeq)
    }
    val banner: @Composable () -> Unit = {
        if (!imeCompact) BannerSlot(
            notice = ui.notice,
            permissionGranted = notificationsGranted,
            prefs = prefs,
            onDismissNotice = holder::dismissNotice,
            onAllow = onAllowNotifications,
            onOpenNotificationSettings = onOpenNotificationSettings,
        )
    }
    val mode: @Composable () -> Unit = {
        if (!imeCompact) {
            ModeRow(
                ui.mode, ui.graphicBandCount,
                // A focused band field would be destroyed by the cross-fade: drop focus first.
                { m -> focus.clearFocus(); holder.setMode(m) },
                holder::setBandCount, stacked = spec.stackedModeRow,
            )
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

    val tone: @Composable (Boolean) -> Unit = { compact ->
        ToneCard(
            state = { ui },
            bass = ToneActions(holder::setBass, holder::bassDragStart, holder::bassDragEnd, holder::resetBass),
            treble = ToneActions(holder::setTreble, holder::trebleDragStart, holder::trebleDragEnd, holder::resetTreble),
            balance = ToneActions(holder::setBalance, holder::balanceDragStart, holder::balanceDragEnd, holder::resetBalance),
            compact = compact,
            largeFont = spec.fontScale >= 1.3f,
        )
    }
    // The Preamp card with the Tone & balance card directly under it, in every slot the preamp card had.
    val footerStack: @Composable (Boolean, Float) -> Unit = { compact, gapDp ->
        Column(verticalArrangement = Arrangement.spacedBy(gapDp.dp)) {
            preamp(compact)
            tone(compact)
        }
    }

    val modeContent: @Composable (Boolean) -> Unit = { compact ->
        val tight = spec.tight && !compact
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
                ParametricListRegion(
                    state = { ui },
                    actions = bandActions,
                    pinchHintSeen = pinchSeen.value,
                    footer = { footerStack(compact, PANE_GAP_DP) },
                )
            }
        }
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
                            chromeDp = curveChromeDp(spec.fontScale, spec.widthDp, parametric),
                            contentMinDp = graphicPanelMinDp(spec.fontScale, compact = false, tight = spec.tight),
                            plotMinDp = if (spec.tight) PLOT_MIN_TIGHT_DP else PLOT_MIN_DP,
                            parametric = parametric,
                            imeCompact = imeCompact,
                            parametricPlotDp = parametricPlotHeight(spec.heightDp),
                            modifier = Modifier.verticalScroll(scroll).testTag("eq-column"),
                            curve = { CurveCard({ ui }, holder::applySuggestedPreamp, fillHeight = true, footerHeight = curveFooterHeight(spec.fontScale, spec.widthDp, parametric), parametric = parametricActions, compact = imeCompact) },
                            mode = mode,
                            content = { modeContent(false) },
                            // Parametric: the preamp card is the last item of the band list.
                            preamp = { if (!parametric) footerStack(false, COLUMN_GAP_DP) },
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
                        flexMinDp = when {
                            imeCompact -> CurveCardTopPad.value + IME_PLOT_DP
                            landscapePeq -> curveChromeDp(spec.fontScale, spec.widthDp, parametric) - (curveHeaderHeight(spec.fontScale).value - landscapeHeaderDp(spec.fontScale)) + PEQ_LANDSCAPE_PLOT_MIN_DP
                            parametric -> curveChromeDp(spec.fontScale, spec.widthDp, parametric) + PEQ_TWO_PANE_PLOT_MIN_DP
                            else -> curveChromeDp(spec.fontScale, spec.widthDp, parametric) + LEFT_PANE_PLOT_MIN_DP
                        },
                        flexMaxDp = if (parametric && !landscapePeq && !imeCompact) {
                            curveChromeDp(spec.fontScale, spec.widthDp, parametric) + PEQ_TWO_PANE_PLOT_MAX_DP
                        } else {
                            Float.MAX_VALUE
                        },
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        slots = listOf(
                            strip,
                            banner,
                            {
                                CurveCard(
                                    { ui }, holder::applySuggestedPreamp, fillHeight = true,
                                    footerHeight = curveFooterHeight(spec.fontScale, spec.widthDp, parametric), parametric = parametricActions,
                                    compact = imeCompact,
                                    headerHeightOverride = if (landscapePeq) PEQ_LANDSCAPE_HEADER else null,
                                    dropEmptyFooter = landscapePeq,
                                )
                            },
                            mode,
                        ),
                    )
                }
                Spacer(Modifier.width(16.dp))
                BoxWithConstraints(Modifier.weight(1f - LEFT_PANE_FRACTION).fillMaxHeight().testTag("right-pane")) {
                    FlexColumn(
                        availableHeight = maxHeight,
                        flexIndex = 0,
                        flexMinDp = if (parametric) PARAMETRIC_LIST_MIN_DP else graphicPanelMinDp(spec.fontScale, compact = true),
                        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("right-pane-scroll"),
                        slots = listOf({ modeContent(true) }, { if (!parametric) footerStack(true, PANE_GAP_DP) }),
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
    parametric: Boolean,
    imeCompact: Boolean,
    parametricPlotDp: Float,
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
        if (parametric) {
            // Curve and mode row pinned, the band list takes the rest (the preamp card is inside the list).
            val modeP = measurables[1].measure(loose)
            val chrome = if (imeCompact) CurveCardTopPad.value else chromeDp
            val desired = if (imeCompact) IME_PLOT_DP else parametricPlotDp
            val gap = (COLUMN_GAP_DP * d).roundToInt()
            val gaps = (if (modeP.height > 0) 1 else 0) + 1
            val pPlan = planParametricColumn(availablePx / d, modeP.height / d, chrome, desired, gaps)
            val minListPx = (PARAMETRIC_LIST_MIN_DP * d).roundToInt()
            val modeBlock = if (modeP.height > 0) modeP.height + gap else 0
            var curveH = ((chrome + pPlan.plotDp) * d).roundToInt()
            if (!pPlan.scrolls) curveH = minOf(curveH, availablePx - modeBlock - gap - minListPx)
            val listH = if (pPlan.scrolls) minListPx else maxOf(minListPx, availablePx - curveH - modeBlock - gap)
            val curveP = measurables[0].measure(Constraints(w, w, curveH, curveH))
            val listP = measurables[2].measure(Constraints(w, w, listH, listH))
            val emptyP = measurables[3].measure(loose)
            val total = curveP.height + modeBlock + gap + listP.height
            return@Layout layout(w, total) {
                var y = 0
                curveP.place(0, y); y += curveP.height + gap
                if (modeP.height > 0) { modeP.place(0, y); y += modeP.height + gap }
                listP.place(0, y)
                emptyP.place(0, 0)
            }
        }
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
    flexMaxDp: Float = Float.MAX_VALUE,
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
        val flexMinPx = (flexMinDp * density).roundToInt()
        val flexMaxPx = if (flexMaxDp == Float.MAX_VALUE) Int.MAX_VALUE else maxOf(flexMinPx, (flexMaxDp * density).roundToInt())
        val flexH = minOf(flexMaxPx, maxOf(flexMinPx, avail - used))
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

package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.dylenq.eq.core.EqMode

/** True when the mode row stacks: a narrow window (320-359 dp) or font scale 1.3 and up. */
fun modeRowStacked(screenWidthDp: Int, fontScale: Float): Boolean = screenWidthDp < 360 || fontScale >= 1.3f

@Composable
fun rememberModeRowStacked(): Boolean =
    modeRowStacked(LocalConfiguration.current.screenWidthDp, LocalDensity.current.fontScale)

/**
 * UI-SPEC "ModeRow": Graphic/Parametric segmented buttons and the band-count menu. In Parametric the menu
 * is invisible, not in semantics and does not take touches, but still occupies its space so the row
 * does not shift.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeRow(
    mode: EqMode,
    bandCount: Int,
    onMode: (EqMode) -> Unit,
    onBandCount: (Int) -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = rememberModeRowStacked(),
) {
    val segmented: @Composable (Modifier) -> Unit = { m ->
        SingleChoiceSegmentedButtonRow(m.testTag("mode-segmented")) {
            SegmentedButton(
                selected = mode == EqMode.GRAPHIC,
                onClick = { if (mode != EqMode.GRAPHIC) onMode(EqMode.GRAPHIC) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("mode-graphic"),
            ) { Text("Graphic") }
            SegmentedButton(
                selected = mode == EqMode.PARAMETRIC,
                onClick = { if (mode != EqMode.PARAMETRIC) onMode(EqMode.PARAMETRIC) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("mode-parametric"),
            ) { Text("Parametric") }
        }
    }
    val bandMenu: @Composable () -> Unit = {
        val visible = mode == EqMode.GRAPHIC
        Box(
            Modifier
                .testTag("band-count-slot")
                .heightIn(min = 48.dp)
                .alpha(if (visible) 1f else 0f)
                .then(
                    if (visible) {
                        Modifier
                    } else {
                        Modifier
                            .clearAndSetSemantics { }
                            .pointerInput(Unit) {
                                // Swallow touches on the Initial pass so the invisible button never fires.
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { BandCountMenu(bandCount, onBandCount) }
    }

    if (stacked) {
        Column(modifier.fillMaxWidth().testTag("mode-row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segmented(Modifier.fillMaxWidth())
            bandMenu()
        }
    } else {
        Row(
            modifier.fillMaxWidth().testTag("mode-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            segmented(Modifier.weight(1f).widthIn(min = 168.dp))
            bandMenu()
        }
    }
}

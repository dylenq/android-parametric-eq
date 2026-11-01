package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The value bubble is shared by the panel; it must follow the slider under the finger, not the last one laid out. */
@RunWith(AndroidJUnit4::class)
class BubbleAnchorTest {
    @get:Rule val rule = EqComposeRule()

    @Test fun theBubbleAnchorsToThePressedSlider() {
        val bubble = BubbleState()
        rule.host {
            Row {
                for (i in 0..2) {
                    VerticalGainSlider(
                        index = i, frequencyHz = 100f * (i + 1), gainDb = { 0f }, pitch = 56.dp,
                        onDragStart = {}, onChange = {}, onDragEnd = {}, onReset = {},
                        bubble = bubble, modifier = Modifier.height(240.dp),
                    )
                }
            }
        }
        rule.compose.waitForIdle()
        for (i in listOf(0, 2, 1)) {
            rule.compose.onNodeWithTag("band-$i").performTouchInput { down(center) }
            rule.compose.waitForIdle()
            val anchored = bubble.sliderCoords
            assertNotNull(anchored)
            val expected = with(rule.compose.density) { (56.dp * i).toPx() }
            assertEquals(expected, anchored!!.positionInRoot().x, 2f)
            rule.compose.onNodeWithTag("band-$i").performTouchInput { up() }
            rule.compose.waitForIdle()
        }
    }
}

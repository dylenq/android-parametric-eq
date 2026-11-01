package com.dylenq.eq.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A card reading a slice recomposes for its own fields, not for each curve update during a drag. */
@RunWith(AndroidJUnit4::class)
class StateSliceTest {
    @get:Rule val rule = EqComposeRule()

    @Test fun onlyAChangeInTheSliceRecomposes() {
        val ui = mutableStateOf(EqUiState())
        var compositions = 0
        rule.host {
            val bass by rememberSlice({ ui.value }) { it.bassDb }
            SideEffect { compositions++ }
            Text("$bass")
        }
        rule.compose.waitForIdle()
        val start = compositions

        repeat(5) { i ->
            ui.value = ui.value.copy(headroomAfterPreampDb = i.toFloat(), suggestedPreampDb = -i.toFloat())
            rule.compose.waitForIdle()
        }
        assertEquals("unrelated fields", start, compositions)

        ui.value = ui.value.copy(bassDb = 3f)
        rule.compose.waitForIdle()
        assertEquals("its own field", start + 1, compositions)
    }
}

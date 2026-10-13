package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The Parametric mode slot of the screen: the accordion band list (one LazyColumn: header, hint or empty
 * state, band rows) with [footer] (the preamp card) as its last item. It is aligned to the top and fills the
 * space it is given; the list scrolls inside it.
 */
@Composable
fun ParametricListRegion(
    state: () -> EqUiState,
    actions: BandListActions,
    pinchHintSeen: Boolean,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().testTag("peq-list"), contentAlignment = Alignment.TopStart) {
        ParametricBandList(
            state = state,
            actions = actions,
            pinchHintSeen = pinchHintSeen,
            modifier = Modifier.fillMaxSize(),
            footer = footer,
        )
    }
}

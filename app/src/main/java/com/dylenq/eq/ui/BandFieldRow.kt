package com.dylenq.eq.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dylenq.eq.core.Band
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class BandField { FREQUENCY, GAIN, Q }

/**
 * Counts the focused fields of one expanded row so a hop between fields stays inside one undo step
 * (UI-SPEC "one undo step per focus session"). Focus moving from field A to field B reports a blur and a
 * gain in quick succession, so the end of the session is deferred by one frame and cancelled when
 * another field took focus in the meantime.
 */
@Stable
internal class FieldFocusSession(
    private val scope: CoroutineScope,
    private val actions: () -> BandListActions,
    private val index: () -> Int,
) {
    var focusedCount = 0
        private set

    fun gained() {
        focusedCount++
        actions().onFieldFocus(index())
    }

    fun lost() {
        focusedCount = (focusedCount - 1).coerceAtLeast(0)
        if (focusedCount == 0) {
            scope.launch {
                withFrameNanos { }
                if (focusedCount == 0) actions().onFieldBlur()
            }
        }
    }

    /** The row is leaving the composition: close the undo group now. */
    fun end() {
        focusedCount = 0
        actions().onFieldBlur()
    }
}

/**
 * The three numeric fields of an expanded row (PEQ-05): frequency, gain and Q. Typed text lives in a
 * local draft; a value that parses and is in range is applied live, clamped and unparsable text only on
 * commit (Next, Done, blur, row collapse, IME dismiss). All rules are in the UI-SPEC "Commit and
 * validation rules"; parsing is [NumericField].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BandFieldRow(index: Int, band: Band, actions: BandListActions, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val actionsNow = rememberUpdatedState(actions)
    val indexNow = rememberUpdatedState(index)
    val session = remember { FieldFocusSession(scope, { actionsNow.value }, { indexNow.value }) }
    val focus = LocalFocusManager.current
    var message by remember { mutableStateOf<Pair<BandField, String>?>(null) }

    // Declared before the fields so it is disposed after them (effects are disposed in reverse order):
    // the fields commit on disposal while the session is still open, so the commit joins its undo step.
    DisposableEffect(Unit) { onDispose { session.end() } }

    // IME dismissed (back gesture) while a field is focused: commit by clearing focus.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible && session.focusedCount > 0) focus.clearFocus()
        imeWasVisible = imeVisible
    }

    val stacked = LocalDensity.current.fontScale >= 1.3f || LocalConfiguration.current.screenWidthDp < 340
    val n = index + 1
    val freq: @Composable (Modifier) -> Unit = { m ->
        NumberField(
            which = BandField.FREQUENCY, label = "Freq Hz", description = "Band $n frequency, hertz",
            tag = "band-field-freq-$index", imeAction = ImeAction.Next, enabled = true,
            modelText = NumericField.formatFrequency(band.freq), session = session,
            parse = NumericField::parseFrequency, format = NumericField::formatFrequency,
            apply = { actionsNow.value.onFieldChange(indexNow.value, it, null, null) },
            message = message, onMessage = { message = it }, modifier = m,
        )
    }
    val gain: @Composable (Modifier) -> Unit = { m ->
        NumberField(
            which = BandField.GAIN, label = "Gain dB", description = "Band $n gain, decibels",
            tag = "band-field-gain-$index", imeAction = ImeAction.Next, enabled = band.kind.hasGain(),
            modelText = if (band.kind.hasGain()) NumericField.formatGain(band.gainDb) else "-", session = session,
            parse = NumericField::parseGain, format = NumericField::formatGain,
            apply = { actionsNow.value.onFieldChange(indexNow.value, null, it, null) },
            message = message, onMessage = { message = it }, modifier = m,
        )
    }
    val q: @Composable (Modifier) -> Unit = { m ->
        NumberField(
            which = BandField.Q, label = "Q", description = "Band $n Q",
            tag = "band-field-q-$index", imeAction = ImeAction.Done, enabled = true,
            modelText = NumericField.formatQ(band.q), session = session,
            parse = NumericField::parseQ, format = NumericField::formatQ,
            apply = { actionsNow.value.onFieldChange(indexNow.value, null, null, it) },
            message = message, onMessage = { message = it }, modifier = m,
        )
    }

    Column(modifier.fillMaxWidth()) {
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                freq(Modifier.fillMaxWidth())
                gain(Modifier.fillMaxWidth())
                q(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                freq(Modifier.weight(0.36f))
                gain(Modifier.weight(0.36f))
                q(Modifier.weight(0.28f))
            }
        }
        val m = message
        if (m != null) {
            Text(
                m.second,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 16.dp)
                    .padding(top = 4.dp, start = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("band-field-message-$index"),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberField(
    which: BandField,
    label: String,
    description: String,
    tag: String,
    imeAction: ImeAction,
    enabled: Boolean,
    modelText: String,
    session: FieldFocusSession,
    parse: (String) -> FieldParse,
    format: (Float) -> String,
    apply: (Float) -> Unit,
    message: Pair<BandField, String>?,
    onMessage: (Pair<BandField, String>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val selectScope = rememberCoroutineScope()
    // The draft survives rotation (rememberSaveable); `dirty` keeps the model from overwriting it.
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(modelText)) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var atFocus by remember { mutableStateOf(modelText) }
    val modelNow = rememberUpdatedState(modelText)
    val applyNow = rememberUpdatedState(apply)

    // Not focused and not mid-edit: show the latest formatted model value (a drag, an undo, a sign flip).
    LaunchedEffect(modelText, focused, dirty) {
        if (!focused && !dirty && value.text != modelText) value = TextFieldValue(modelText)
    }

    /** Pitfall 5: nothing happens when the text is what it was at focus. */
    fun commit() {
        val text = value.text
        if (!enabled || !NumericField.shouldCommit(atFocus, text)) {
            dirty = false
            return
        }
        when (val r = parse(text)) {
            is FieldParse.Value -> {
                apply(r.value)
                value = TextFieldValue(format(r.value))
            }
            is FieldParse.Clamped -> {
                apply(r.value)
                value = TextFieldValue(format(r.value))
                onMessage(which to r.message)
            }
            is FieldParse.Invalid -> {
                value = TextFieldValue(modelNow.value)
                onMessage(which to r.message)
            }
        }
        atFocus = value.text
        dirty = false
    }
    val commitNow = rememberUpdatedState(::commit)

    // The row collapsed or left the screen while this field had unsaved text: apply it, do not touch state.
    val draftNow = rememberUpdatedState(value.text)
    val atFocusNow = rememberUpdatedState(atFocus)
    DisposableEffect(Unit) {
        onDispose {
            if (enabled && NumericField.shouldCommit(atFocusNow.value, draftNow.value)) {
                when (val r = parse(draftNow.value)) {
                    is FieldParse.Value -> applyNow.value(r.value)
                    is FieldParse.Clamped -> applyNow.value(r.value)
                    is FieldParse.Invalid -> {}
                }
            }
        }
    }

    val isError = message?.first == which
    OutlinedTextField(
        value = if (enabled) value else TextFieldValue("-"),
        onValueChange = { new ->
            if (new.text.length > NumericField.MAX_LENGTH) return@OutlinedTextField
            val changed = new.text != value.text
            value = new
            if (!changed) return@OutlinedTextField
            dirty = true
            if (message?.first == which) onMessage(null)
            val r = parse(new.text)
            if (r is FieldParse.Value) apply(r.value)
        },
        enabled = enabled,
        singleLine = true,
        isError = isError,
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Next) },
            onDone = { focusManager.clearFocus() },
        ),
        modifier = modifier
            .onFocusChanged { st ->
                val now = st.isFocused
                if (now == focused) return@onFocusChanged
                focused = now
                if (now) {
                    atFocus = modelNow.value
                    session.gained()
                    // The tap that focused the field places the cursor after this callback; select all once it settled.
                    selectScope.launch {
                        kotlinx.coroutines.delay(120)
                        if (focused) value = value.copy(selection = TextRange(0, value.text.length))
                    }
                } else {
                    commitNow.value()
                    session.lost()
                }
            }
            .semantics {
                contentDescription = description
                if (!enabled) stateDescription = "No gain for this filter type"
                val m = message
                if (m != null && m.first == which) error(m.second)
            }
            .testTag(tag),
    )
}

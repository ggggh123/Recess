package com.example.personal_studio.ui.components

import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.input.ImeAction

/**
 * A [TextFieldState] for a field whose text is owned elsewhere as a `String` (a view model's
 * UI state, a `remember`ed variable), for use with the state-based `BasicTextField`.
 *
 * Why: the `String` / `TextFieldValue` fields apply key events to the value of the last
 * composition. IMEs that type a paired quote commit “” and then send ←; the ← was applied to
 * the text before the commit and written back, so the quotes vanished and the cursor moved.
 * The state-based field edits the current text, so the field must own its text.
 *
 * The field is the source of truth while typing: every edit goes out through [onValueChange].
 * [value] coming back is either the echo of an edit (ignored, even when it arrives a frame late
 * and older edits are still on their way) or a change made outside the field, such as a prefill
 * after loading or a reset after saving, which replaces the text with the cursor at the end.
 * An owner that rejects an edit should constrain the field instead (`InputTransformation`),
 * because a rejection does not change [value] and so is not seen.
 */
@Composable
fun rememberTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = remember { TextFieldState(value) }
    val echoes = remember { EchoFilter(value) }
    val latestOnChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (echoes.edited(text)) latestOnChange(text)
        }
    }
    // Keyed on [value]: a stale value passed again by an unrelated recomposition is not a change.
    LaunchedEffect(value) {
        if (echoes.external(value)) state.setTextAndPlaceCursorAtEnd(value)
    }
    return state
}

/**
 * Tells the field's own edits apart from changes made by its owner (see [rememberTextFieldState]).
 * Edits sent out and not yet seen back are kept in order; a value equal to one of them is its
 * echo, which also acknowledges every older pending edit.
 */
internal class EchoFilter(initial: String) {
    /** What the field holds, as far as this filter knows. */
    private var current = initial
    private val pending = ArrayDeque<String>()

    /** The field now holds [text]; true when the owner should be told. */
    fun edited(text: String): Boolean {
        if (text == current) return false
        current = text
        pending.addLast(text)
        return true
    }

    /** The owner's value is now [value]; true when the field should take it over. */
    fun external(value: String): Boolean {
        val echo = pending.indexOf(value)
        if (echo >= 0) {
            repeat(echo + 1) { pending.removeFirst() }
            return false
        }
        pending.clear()
        if (value == current) return false
        current = value
        return true
    }
}

/**
 * The state-based field reports the IME action through a [KeyboardActionHandler]; this runs the
 * matching lambda of the [KeyboardActions] the caller gave for [imeAction], or the default.
 */
internal fun KeyboardActions.toHandler(imeAction: ImeAction): KeyboardActionHandler? {
    val action: (KeyboardActionScope.() -> Unit)? = when (imeAction) {
        ImeAction.Done -> onDone
        ImeAction.Go -> onGo
        ImeAction.Next -> onNext
        ImeAction.Previous -> onPrevious
        ImeAction.Search -> onSearch
        ImeAction.Send -> onSend
        else -> null
    }
    action ?: return null
    return KeyboardActionHandler { performDefault ->
        action(object : KeyboardActionScope {
            override fun defaultKeyboardAction(imeAction: ImeAction) = performDefault()
        })
    }
}

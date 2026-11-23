package com.example.personal_studio.feature.chat.ui

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.personal_studio.ui.theme.PaperCanvas
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chinese IMEs type a paired quote by committing “” and then sending ← to put the cursor
 * between them, two calls in quick succession. The composer must keep both quotes.
 */
@RunWith(AndroidJUnit4::class)
class ComposerImeTest {

    @get:Rule val rule = createComposeRule()

    private fun typePairedQuotes(view: View) {
        val ic = view.onCreateInputConnection(EditorInfo()) ?: error("no input connection")
        ic.commitText("“”", 1)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test fun pairedQuotesFromTheImeAreKept() {
        val state = TextFieldState("他说")
        lateinit var view: View
        val focus = FocusRequester()
        rule.setContent {
            view = LocalView.current
            PaperCanvas {
                ChatComposer(
                    input = remember { state },
                    images = emptyList(),
                    sending = false,
                    canAttach = true,
                    onAttach = {},
                    onRemoveImage = {},
                    onSend = {},
                    onStop = {},
                    focusRequester = focus,
                )
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()

        rule.runOnUiThread { typePairedQuotes(view) }
        rule.waitForIdle()

        assertEquals("他说“”", state.text.toString())
        assertEquals(TextRange(3), state.selection)

        // Typing goes between the quotes.
        rule.runOnUiThread { view.onCreateInputConnection(EditorInfo())!!.commitText("好", 1) }
        rule.waitForIdle()
        assertEquals("他说“好”", state.text.toString())
    }
}

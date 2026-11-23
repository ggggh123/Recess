package com.example.personal_studio.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.personal_studio.data.scanner.BitmapIo
import com.example.personal_studio.ui.theme.PaperCanvas
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TgTextField] driven like the forms drive it: the text lives in a StateFlow (a view model)
 * and comes back a frame later. Chinese IMEs type a paired quote by committing “” and then
 * sending ←; both quotes must stay, and outside changes (a clear button) must still apply.
 */
@RunWith(AndroidJUnit4::class)
class TgTextFieldImeTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var view: View
    private val owner = MutableStateFlow("")

    private fun showField() {
        rule.setContent {
            view = LocalView.current
            val text by owner.collectAsStateWithLifecycle()
            PaperCanvas {
                TgTextField(text, { owner.value = it }, label = "标题", modifier = Modifier.testTag("field"))
            }
        }
        rule.onNodeWithTag("field").performClick()
        rule.waitForIdle()
    }

    private fun ime() = view.onCreateInputConnection(EditorInfo()) ?: error("no input connection")

    @Test fun pairedQuotesSurviveAStateFlowOwner() {
        showField()
        rule.runOnUiThread { ime().commitText("他说", 1) }
        rule.waitForIdle()

        rule.runOnUiThread {
            val ic = ime()
            ic.commitText("“”", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        }
        rule.waitForIdle()
        assertEquals("他说“”", owner.value)

        rule.runOnUiThread { ime().commitText("好", 1) }
        rule.waitForIdle()
        assertEquals("他说“好”", owner.value)
    }

    @Test fun aChangeFromTheOwnerReplacesTheText() {
        showField()
        rule.runOnUiThread { ime().commitText("草稿", 1) }
        rule.waitForIdle()
        assertEquals("草稿", owner.value)

        owner.value = "" // e.g. the search bar's clear button
        rule.waitForIdle()
        rule.runOnUiThread { ime().commitText("新", 1) }
        rule.waitForIdle()
        assertEquals("新", owner.value)
    }

    @Test fun quarterTurnsRotateClockwise() {
        // 2×1: red on the left, blue on the right.
        val src = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
            setPixel(1, 0, Color.BLUE)
        }
        val turned = BitmapIo.rotateQuarters(src, 1)
        assertEquals(1, turned.width)
        assertEquals(2, turned.height)
        // Clockwise: the left (red) pixel ends up on top.
        assertEquals(Color.RED, turned.getPixel(0, 0))
        assertEquals(Color.BLUE, turned.getPixel(0, 1))
        assertEquals(src, BitmapIo.rotateQuarters(src, 4))
    }
}

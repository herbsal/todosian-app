package com.isotjs.todosian.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

/**
 * Task text fields wrap onto more lines when the text gets long, so the whole task stays readable.
 * A task is still one line in the note: the keyboard shows "Done" instead of a line break, and
 * line breaks from pasting or a hardware keyboard become spaces.
 */
internal object TaskTextField {
    const val MAX_LINES = 6
    val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)

    fun oneLine(text: String): String = text.replace(Regex("""[ \t]*[\r\n]+[ \t]*"""), " ")
}

/**
 * A tap on an empty spot of a task sheet leaves the text field and hides the keyboard. Taps on
 * buttons, chips and fields are theirs and never get here.
 */
internal fun Modifier.clearFocusOnTap(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    pointerInput(Unit) {
        detectTapGestures {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
}

/**
 * Back while the keyboard is open only closes the keyboard (and leaves the text field); the
 * sheet stays. Put it inside a task sheet's content so it comes before the sheet's own Back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardFirstBackHandler() {
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = imeVisible) {
        keyboard?.hide()
        focusManager.clearFocus()
    }
}

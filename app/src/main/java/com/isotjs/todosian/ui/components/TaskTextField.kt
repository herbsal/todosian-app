package com.isotjs.todosian.ui.components

import androidx.compose.foundation.text.KeyboardOptions
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

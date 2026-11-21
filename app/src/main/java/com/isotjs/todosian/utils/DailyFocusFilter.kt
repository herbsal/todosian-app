package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.settings.DailyFocusMode

/** Which tasks the daily focus shows; shared by the daily focus screen and the widget. */
internal object DailyFocusFilter {
    /** [today] as ISO date (yyyy-MM-dd), compared with the task's due date as text. */
    fun matches(todo: Todo, mode: DailyFocusMode, today: String): Boolean {
        val due = todo.dueDate ?: return false
        return when (mode) {
            DailyFocusMode.TODAY -> due == today
            DailyFocusMode.OVERDUE -> due < today
            DailyFocusMode.TODAY_AND_OVERDUE -> due <= today
        }
    }
}

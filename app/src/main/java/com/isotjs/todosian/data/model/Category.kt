package com.isotjs.todosian.data.model

import android.net.Uri

data class Category(
    val displayName: String,
    val uri: Uri,
    val todoCount: Int,
    val doneCount: Int,
    val dueTodayCount: Int,
    val overdueCount: Int,
    /** The open tasks in file order, for the task preview and the calendar on the home screen. */
    val openTodos: List<Todo> = emptyList(),
)

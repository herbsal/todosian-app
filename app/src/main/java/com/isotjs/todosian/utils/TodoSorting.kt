package com.isotjs.todosian.utils

import com.isotjs.todosian.data.model.Todo
import com.isotjs.todosian.data.model.priorityRank
import com.isotjs.todosian.data.settings.TodoSort
import java.text.Collator
import java.util.Locale

/**
 * Orders a task list. Every order except [TodoSort.FILE_ORDER] moves a task together with its
 * subtasks, which keep their file order below it; ties keep the file order.
 */
object TodoSorting {

    fun sort(
        todos: List<Todo>,
        sort: TodoSort,
        groupName: String = "",
        definitions: TagDefinitions = TagDefinitions.EMPTY,
        locale: Locale = Locale.getDefault(),
    ): List<Todo> {
        val byLine = todos.sortedBy { it.lineIndex }
        val comparator: Comparator<Todo> = when (sort) {
            TodoSort.FILE_ORDER -> return byLine
            TodoSort.PRIORITY_HIGH_TO_LOW -> compareByDescending { it.priority.priorityRank() }
            TodoSort.CREATED_DATE_NEWEST_FIRST ->
                compareByDescending<Todo> { it.createdDate != null }.thenByDescending { it.createdDate.orEmpty() }
            TodoSort.DUE_DATE_EARLIEST_FIRST -> compareBy<Todo> { it.dueDate == null }.thenBy { it.dueDate.orEmpty() }
            TodoSort.REMINDER_EARLIEST_FIRST -> compareBy<Todo> { it.reminder == null }.thenBy { it.reminder.orEmpty() }
            TodoSort.TITLE_A_Z -> {
                val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
                Comparator { a, b -> collator.compare(a.text.trim(), b.text.trim()) }
            }
            TodoSort.TAG_GROUP -> {
                val group = definitions.groups.firstOrNull { it.name != null && it.name.equals(groupName, ignoreCase = true) }
                    ?: return byLine
                compareBy { tagRank(it.text, group) }
            }
        }
        return keepingSubtasks(byLine, comparator)
    }

    /** Position of the task's first keyword of [group] in the keyword note; tasks without one come last. */
    fun tagRank(text: String, group: TagGroup): Int {
        val tags = TagText.tagsIn(text)
        val index = group.terms.indexOfFirst { term -> tags.any { term.matches(it) } }
        return if (index < 0) Int.MAX_VALUE else index
    }

    private fun keepingSubtasks(byLine: List<Todo>, comparator: Comparator<Todo>): List<Todo> {
        val blocks = ArrayList<MutableList<Todo>>()
        for (todo in byLine) {
            if (todo.indentLevel == 0 || blocks.isEmpty()) blocks.add(mutableListOf(todo)) else blocks.last().add(todo)
        }
        // sortedWith is stable, so equal tasks keep their file order.
        return blocks.sortedWith { a, b -> comparator.compare(a.first(), b.first()) }.flatten()
    }
}

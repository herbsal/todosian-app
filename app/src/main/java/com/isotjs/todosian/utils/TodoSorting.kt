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
            TodoSort.TAG_GROUP -> {
                val group = definitions.groups.firstOrNull { it.name != null && it.name.equals(groupName, ignoreCase = true) }
                    ?: return byLine
                compareBy { tagRank(it.text, group) }
            }
            else -> todoComparator(sort, locale)
        }
        return keepingSubtasks(byLine, comparator)
    }

    /**
     * Orders tasks of several lists (daily focus, agenda, the lists tab) in one order: like [sort]
     * inside a list, each task with the tag note of its own list. [TodoSort.FILE_ORDER] keeps the
     * given order. Ties keep the given order.
     */
    fun <T> sortAcrossLists(
        items: List<T>,
        sort: TodoSort,
        groupName: String,
        todoOf: (T) -> Todo,
        definitionsOf: (T) -> TagDefinitions,
        locale: Locale = Locale.getDefault(),
    ): List<T> {
        val comparator: Comparator<T> = when (sort) {
            TodoSort.FILE_ORDER -> return items
            TodoSort.TAG_GROUP -> compareBy { item ->
                definitionsOf(item).groups.firstOrNull { it.name != null && it.name.equals(groupName, ignoreCase = true) }
                    ?.let { tagRank(todoOf(item).text, it) } ?: Int.MAX_VALUE
            }
            else -> {
                val byTodo = todoComparator(sort, locale)
                Comparator { a, b -> byTodo.compare(todoOf(a), todoOf(b)) }
            }
        }
        return items.sortedWith(comparator)
    }

    private fun todoComparator(sort: TodoSort, locale: Locale): Comparator<Todo> = when (sort) {
        TodoSort.PRIORITY_HIGH_TO_LOW -> compareByDescending { it.priority.priorityRank() }
        TodoSort.CREATED_DATE_NEWEST_FIRST ->
            compareByDescending<Todo> { it.createdDate != null }.thenByDescending { it.createdDate.orEmpty() }
        TodoSort.DUE_DATE_EARLIEST_FIRST -> compareBy<Todo> { it.dueDate == null }.thenBy { it.dueDate.orEmpty() }
        TodoSort.REMINDER_EARLIEST_FIRST -> compareBy<Todo> { it.reminder == null }.thenBy { it.reminder.orEmpty() }
        TodoSort.TITLE_A_Z -> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
            Comparator { a, b -> collator.compare(a.text.trim(), b.text.trim()) }
        }
        TodoSort.FILE_ORDER, TodoSort.TAG_GROUP -> Comparator { _, _ -> 0 }
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

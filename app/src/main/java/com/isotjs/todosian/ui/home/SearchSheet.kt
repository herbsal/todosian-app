package com.isotjs.todosian.ui.home

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.data.model.Category
import com.isotjs.todosian.data.model.Todo

private sealed interface SearchHit {
    data class ListHit(val category: Category) : SearchHit
    data class TaskHit(val category: Category, val todo: Todo) : SearchHit
}

private const val MAX_HITS = 200

/** Searches tasks and list names in the app; everything else goes to Obsidian's search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(
    categories: List<Category>,
    onOpenCategory: (Uri) -> Unit,
    onPreview: (Uri) -> Unit,
    onSearchInObsidian: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hits = remember(query, categories) { search(categories, query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            TextButton(onClick = { onSearchInObsidian(query.trim()) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.search_in_obsidian), modifier = Modifier.padding(start = 6.dp))
            }
            if (query.isNotBlank() && hits.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(hits) { hit ->
                    when (hit) {
                        is SearchHit.ListHit -> ListItem(
                            headlineContent = { Text(hit.category.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(R.string.search_list)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { onPreview(hit.category.uri) }) {
                                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = stringResource(R.string.preview_show_note))
                                }
                            },
                            modifier = Modifier.clickable { onOpenCategory(hit.category.uri) },
                        )
                        is SearchHit.TaskHit -> ListItem(
                            headlineContent = { Text(hit.todo.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(hit.category.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Icon(
                                    if (hit.todo.isDone) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { onOpenCategory(hit.category.uri) },
                        )
                    }
                }
            }
        }
    }
}

/** Every word of [query] must appear, in any order and case; open tasks before done ones. */
private fun search(categories: List<Category>, query: String): List<SearchHit> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    fun matches(text: String) = text.lowercase().let { lower -> words.all { it in lower } }
    val lists = categories.filter { matches(it.displayName) }.map { SearchHit.ListHit(it) }
    val tasks = categories.flatMap { category -> category.todos.filter { matches(it.text) }.map { SearchHit.TaskHit(category, it) } }
        .sortedBy { it.todo.isDone }
    return (lists + tasks).take(MAX_HITS)
}

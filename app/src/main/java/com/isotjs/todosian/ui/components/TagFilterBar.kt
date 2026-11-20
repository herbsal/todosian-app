package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.utils.FilterTerm

/**
 * A row of keyword chips above a task list. Selecting chips shows only matching tasks; chips of
 * the same group are alternatives, chips of different groups must all apply.
 */
@Composable
fun TagFilterBar(
    terms: List<FilterTerm>,
    selectedKeys: Set<String>,
    onToggle: (FilterTerm) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (terms.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (selectedKeys.isNotEmpty()) {
            item(key = "clear") {
                AssistChip(
                    onClick = onClear,
                    label = { Text(text = stringResource(R.string.tag_filter_clear)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    },
                )
            }
        } else {
            item(key = "icon") {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = stringResource(R.string.tag_filter),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        items(items = terms, key = { it.key }) { term ->
            FilterChip(
                selected = term.key in selectedKeys,
                onClick = { onToggle(term) },
                label = { Text(text = term.label) },
            )
        }
    }
}

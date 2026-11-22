package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagHit
import com.isotjs.todosian.utils.TagText

/**
 * One chip per keyword of the folder's keyword note. Tapping sets or clears the keyword's tag in
 * [text]; in a named group (e.g. "Effort") only one keyword is set at a time.
 * Keywords [recognized] in the words show as selected too; tapping one leaves it out, and picking
 * another keyword of such a group drops the recognized one ([onOverride]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagQuickPicker(
    definitions: TagDefinitions,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    recognized: List<TagHit> = emptyList(),
    onOverride: (TagHit) -> Unit = {},
) {
    if (definitions.isEmpty) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        definitions.groups.forEach { group ->
            group.name?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.terms.forEach { term ->
                    val hit = recognized.firstOrNull { it.term == term }
                    FilterChip(
                        selected = hit != null || TagText.hasTerm(text, term),
                        onClick = {
                            if (hit != null) {
                                onOverride(hit)
                            } else {
                                if (group.exclusive) recognized.filter { it.group == group }.forEach(onOverride)
                                onTextChange(TagText.toggle(text, group, term))
                            }
                        },
                        label = { Text(text = term.label) },
                    )
                }
            }
        }
    }
}

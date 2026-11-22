package com.isotjs.todosian.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.utils.TagDefinitions
import com.isotjs.todosian.utils.TagDisplay
import com.isotjs.todosian.utils.TagHit
import com.isotjs.todosian.utils.TagText

/**
 * One chip per keyword of the folder's keyword note. Tapping sets or clears the keyword's tag in
 * [text]; in a named group (e.g. "Effort") only one keyword is set at a time.
 * Keywords [recognized] in the words show as selected too; tapping one leaves it out, and picking
 * another keyword of such a group drops the recognized one ([onOverride]).
 */
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        definitions.groups.forEach { group ->
            // One row per group, its name in front ("Dauer", "Energie", "Person"), each chip with the group's symbol.
            val icon = TagDisplay.iconFor(group)
            MetaRow(label = group.name) {
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
                        label = { Text(text = "$icon ${term.label}", maxLines = 1) },
                    )
                }
            }
        }
    }
}

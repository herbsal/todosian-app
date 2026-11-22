package com.isotjs.todosian.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.isotjs.todosian.R
import com.isotjs.todosian.utils.MonthlyDay
import com.isotjs.todosian.utils.RecurrenceEngine
import com.isotjs.todosian.utils.RecurrenceFrequency
import com.isotjs.todosian.utils.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Picks how a task repeats instead of typing Obsidian Tasks text: how often, every how many,
 * on which days, and whether to count from completion ("when done"). The resulting text and the
 * next date show below. Rules the picker can't show are edited as text ("Enter your own text").
 * [start] is the task's date the choices are made from (its weekday, its day of the month).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecurrencePickerDialog(
    initial: String?,
    start: LocalDate,
    dueDate: String?,
    scheduledDate: String?,
    startDate: String?,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val parsed = remember(initial) { RecurrenceRule.parse(initial) }
    var rule by remember { mutableStateOf(parsed) }
    var custom by remember { mutableStateOf(!initial.isNullOrBlank() && parsed == null) }
    var customText by remember { mutableStateOf(initial.orEmpty()) }

    val resultText: String? = if (custom) customText.trim().ifEmpty { null } else rule?.text
    val next = resultText?.let { RecurrenceEngine.nextOccurrence(it, dueDate, scheduledDate, startDate)?.reference }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.recurrence_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (custom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it.replace("\n", " ") },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.recurrence_custom_label)) },
                        placeholder = { Text(text = stringResource(R.string.recurrence_custom_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (resultText != null && next == null) {
                        Text(
                            text = stringResource(R.string.recurrence_custom_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (RecurrenceRule.parse(customText) != null || customText.isBlank()) {
                        TextButton(onClick = {
                            rule = RecurrenceRule.parse(customText)
                            custom = false
                        }) { Text(text = stringResource(R.string.recurrence_choices)) }
                    }
                } else {
                    val current = rule
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = current == null,
                            onClick = { rule = null },
                            label = { Text(text = stringResource(R.string.recurrence_none)) },
                        )
                        RecurrenceFrequency.entries.forEach { frequency ->
                            FilterChip(
                                selected = current?.frequency == frequency,
                                onClick = {
                                    if (current?.frequency != frequency) {
                                        rule = RecurrenceRule.startingFrom(frequency, start).copy(whenDone = current?.whenDone ?: false)
                                    }
                                },
                                label = { Text(text = stringResource(frequencyLabel(frequency))) },
                            )
                        }
                    }

                    if (current != null && current.frequency != RecurrenceFrequency.WEEKDAYS) {
                        IntervalStepper(
                            value = current.interval,
                            unit = stringResource(unitLabel(current.frequency)),
                            onChange = { n -> rule = current.copy(interval = n) },
                        )
                    }

                    if (current != null) when (current.frequency) {
                        RecurrenceFrequency.WEEKLY -> {
                            Text(text = stringResource(R.string.recurrence_on_days), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DayOfWeek.entries.forEach { day ->
                                    val on = day in current.weekdays
                                    FilterChip(
                                        selected = on,
                                        onClick = {
                                            val days = if (on) current.weekdays - day else current.weekdays + day
                                            // At least one day: without any, "every week" counts from the date.
                                            rule = current.copy(weekdays = days.ifEmpty { setOf(day) })
                                        },
                                        label = { Text(text = day.getDisplayName(TextStyle.SHORT, locale)) },
                                    )
                                }
                            }
                        }
                        RecurrenceFrequency.MONTHLY -> {
                            val options = buildList<MonthlyDay> {
                                add(MonthlyDay.Day(start.dayOfMonth))
                                RecurrenceRule.weekOfMonth(start)?.let { add(MonthlyDay.NthWeekday(it, start.dayOfWeek)) }
                                add(MonthlyDay.LastDay)
                                if (RecurrenceRule.isLastWeekOfMonth(start)) add(MonthlyDay.LastWeekday(start.dayOfWeek))
                                if (current.monthly != MonthlyDay.SameDay && current.monthly !in this) add(0, current.monthly)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                options.forEach { option ->
                                    FilterChip(
                                        selected = current.monthly == option ||
                                            current.monthly == MonthlyDay.SameDay && option == MonthlyDay.Day(start.dayOfMonth),
                                        onClick = { rule = current.copy(monthly = option) },
                                        label = { Text(text = monthlyLabel(option, locale)) },
                                    )
                                }
                            }
                        }
                        RecurrenceFrequency.YEARLY -> if (current.interval == 1) {
                            val options = buildList<Pair<Month, Int?>> {
                                add(start.month to start.dayOfMonth)
                                add(start.month to null)
                                if (current.yearMonth != null && (current.yearMonth to current.yearDay) !in this) add(0, current.yearMonth to current.yearDay)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                options.forEach { (m, d) ->
                                    FilterChip(
                                        selected = if (current.yearMonth == null) m == start.month && d == start.dayOfMonth else current.yearMonth == m && current.yearDay == d,
                                        onClick = { rule = current.copy(yearMonth = m, yearDay = d) },
                                        label = { Text(text = yearlyLabel(m, d, locale)) },
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }

                    if (current != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(text = stringResource(R.string.recurrence_when_done), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = stringResource(R.string.recurrence_when_done_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = current.whenDone, onCheckedChange = { rule = current.copy(whenDone = it) })
                        }
                    }
                    TextButton(onClick = {
                        customText = rule?.text.orEmpty()
                        custom = true
                    }) { Text(text = stringResource(R.string.recurrence_custom)) }
                }

                if (resultText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (next != null) {
                                val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEdMMMyyyy") }
                                Text(
                                    text = stringResource(R.string.recurrence_next, next.format(DateTimeFormatter.ofPattern(pattern, locale))),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                text = stringResource(R.string.recurrence_obsidian, resultText),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(resultText) }) { Text(text = stringResource(R.string.recurrence_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun IntervalStepper(value: Int, unit: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.recurrence_every), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, enabled = value > 1) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.recurrence_less))
        }
        Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange((value + 1).coerceAtMost(99)) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.recurrence_more))
        }
        Text(text = unit, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun frequencyLabel(frequency: RecurrenceFrequency): Int = when (frequency) {
    RecurrenceFrequency.DAILY -> R.string.recurrence_daily
    RecurrenceFrequency.WEEKDAYS -> R.string.recurrence_weekdays
    RecurrenceFrequency.WEEKLY -> R.string.recurrence_weekly
    RecurrenceFrequency.MONTHLY -> R.string.recurrence_monthly
    RecurrenceFrequency.YEARLY -> R.string.recurrence_yearly
}

private fun unitLabel(frequency: RecurrenceFrequency): Int = when (frequency) {
    RecurrenceFrequency.DAILY, RecurrenceFrequency.WEEKDAYS -> R.string.recurrence_unit_days
    RecurrenceFrequency.WEEKLY -> R.string.recurrence_unit_weeks
    RecurrenceFrequency.MONTHLY -> R.string.recurrence_unit_months
    RecurrenceFrequency.YEARLY -> R.string.recurrence_unit_years
}

@Composable
private fun monthlyLabel(day: MonthlyDay, locale: Locale): String = when (day) {
    MonthlyDay.SameDay -> stringResource(R.string.recurrence_monthly)
    is MonthlyDay.Day -> stringResource(R.string.recurrence_monthly_day, day.day)
    is MonthlyDay.NthWeekday -> stringResource(R.string.recurrence_monthly_nth, day.nth, day.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
    MonthlyDay.LastDay -> stringResource(R.string.recurrence_monthly_last_day)
    is MonthlyDay.LastWeekday -> stringResource(R.string.recurrence_monthly_last_weekday, day.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
}

@Composable
private fun yearlyLabel(month: Month, day: Int?, locale: Locale): String {
    if (day == null) return stringResource(R.string.recurrence_yearly_last, month.getDisplayName(TextStyle.FULL_STANDALONE, locale))
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "dMMMM") }
    // A leap year, so the 29th of February can be shown too.
    val date = LocalDate.of(2024, month, day.coerceAtMost(month.length(true)))
    return stringResource(R.string.recurrence_yearly_on, date.format(DateTimeFormatter.ofPattern(pattern, locale)))
}

/** A short line for a recurrence in the picker's words, e.g. "Every 2 weeks, Mon, Fri"; other rules as written. */
@Composable
internal fun recurrenceSummary(text: String): String {
    val rule = RecurrenceRule.parse(text) ?: return text
    val locale = LocalConfiguration.current.locales[0]
    val n = rule.interval
    val every = if (n > 1) stringResource(R.string.recurrence_summary_every, n, stringResource(unitLabel(rule.frequency))) else null
    val parts = mutableListOf(every ?: stringResource(frequencyLabel(rule.frequency)))
    when (rule.frequency) {
        RecurrenceFrequency.WEEKLY -> if (rule.weekdays.isNotEmpty()) {
            parts += rule.weekdays.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        }
        RecurrenceFrequency.MONTHLY -> if (rule.monthly != MonthlyDay.SameDay) parts += monthlyLabel(rule.monthly, locale)
        RecurrenceFrequency.YEARLY -> rule.yearMonth?.let { parts += yearlyLabel(it, rule.yearDay, locale) }
        else -> Unit
    }
    if (rule.whenDone) parts += stringResource(R.string.recurrence_when_done_short)
    return parts.joinToString(", ")
}

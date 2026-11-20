package com.adam.insightjournal.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adam.insightjournal.data.model.Mood
import com.adam.insightjournal.insights.MoodStats
import com.adam.insightjournal.insights.Streaks
import com.adam.insightjournal.insights.WeekDigest
import com.adam.insightjournal.insights.WeeklySummary
import java.time.LocalDate
import com.adam.insightjournal.ai.AiSettings
import com.adam.insightjournal.ui.ai.rememberAiGate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val shortDate = DateTimeFormatter.ofPattern("MMM d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(viewModel: InsightsViewModel, aiSettings: AiSettings, bottomBar: @Composable () -> Unit) {
    val aiGate = rememberAiGate(aiSettings)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val summaryRequest by viewModel.summaryRequest.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Insights") }) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val state = uiState) {
                InsightsUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is InsightsUiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is InsightsUiState.Loaded -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    WeekCard(
                        week = state.week,
                        summary = state.summary,
                        summaryIsCurrent = state.summaryIsCurrent,
                        request = summaryRequest,
                        aiEnabled = viewModel.aiEnabled,
                        onSummarize = { aiGate.run(viewModel::summarizeWeek) },
                    )
                    MoodCard(state.mood, state.moodRange, viewModel::onMoodRangeChange)
                    StreakCard(state.streaks)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ---- The past week ----

@Composable
private fun WeekCard(
    week: WeekDigest,
    summary: WeeklySummary?,
    summaryIsCurrent: Boolean,
    request: SummaryRequest,
    aiEnabled: Boolean,
    onSummarize: () -> Unit,
) {
    InsightCard(title = "Your week", subtitle = dateRange(week.start, week.end)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(week.entries.size.toString(), if (week.entries.size == 1) "entry" else "entries", Modifier.weight(1f))
            Stat("${week.daysWritten}/7", "days", Modifier.weight(1f))
            Stat(week.wordCount.toString(), "words", Modifier.weight(1f))
            Stat(week.averageMood?.let { Mood.emoji(it.roundToInt()) } ?: "–", "mood", Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        if (summary != null) {
            if (!summaryIsCurrent) {
                Note(
                    "Summary of ${dateRange(summary.periodStart, summary.periodEnd)}. " +
                        "Your journal has changed since it was written."
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(summary.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
        }

        when {
            week.isEmpty ->
                Note("Nothing written in the past 7 days. Write an entry or a quick note and a summary can be made here.")
            !aiEnabled ->
                Note("AI summaries are off: add ANTHROPIC_API_KEY to local.properties and rebuild to turn them on.")
            request == SummaryRequest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Note("Reading your week…")
            }
            else -> {
                if (request is SummaryRequest.Failed) {
                    Text(
                        request.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    summary == null -> Button(onClick = onSummarize) { Text("Summarize my week") }
                    !summaryIsCurrent -> OutlinedButton(onClick = onSummarize) { Text("Update summary") }
                    // Up to date: nothing to offer, the text above is the summary of what's there.
                }
            }
        }
    }
}

// ---- Mood ----

@Composable
private fun MoodCard(stats: MoodStats, range: MoodRange, onRangeChange: (MoodRange) -> Unit) {
    InsightCard(title = "Mood trends") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoodRange.entries.forEach { option ->
                FilterChip(
                    selected = option == range,
                    onClick = { onRangeChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val average = stats.average
        if (average == null) {
            Note(
                "No moods recorded in the past ${range.label}. Pick a face when you write an entry " +
                    "and your trend shows up here."
            )
            return@InsightCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Mood.emoji(average.roundToInt()), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "${formatMood(average)} average · ${Mood.label(average.roundToInt())}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    comparison(average, stats.previousAverage, range, stats.ratedEntryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        MoodLineChart(stats.days, stats.from, stats.to)

        SectionLabel("By day of the week")
        WeekdayBars(stats.byWeekday)

        SectionLabel("How often you felt each way")
        MoodDistribution(stats.distribution)
    }
}

private fun comparison(average: Double, previous: Double?, range: MoodRange, ratedCount: Int): String {
    val basis = if (ratedCount == 1) "from 1 entry" else "from $ratedCount entries"
    if (previous == null) return basis
    val change = average - previous
    val versus = "the ${range.label} before"
    return when {
        abs(change) < 0.05 -> "Same as $versus, $basis"
        change > 0 -> "Up ${formatMood(change)} on $versus, $basis"
        else -> "Down ${formatMood(-change)} on $versus, $basis"
    }
}

// ---- Streak ----

@Composable
private fun StreakCard(streaks: Streaks) {
    InsightCard(title = "Writing streak") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(days(streaks.current), "current", Modifier.weight(1f))
            Stat(days(streaks.longest), "longest", Modifier.weight(1f))
        }
    }
}

private fun days(count: Int): String = if (count == 1) "1 day" else "$count days"

// ---- Shared pieces ----

@Composable
private fun InsightCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun dateRange(start: LocalDate, end: LocalDate): String =
    "${start.format(shortDate)} – ${end.format(shortDate)}"

package com.adam.insightjournal.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adam.insightjournal.data.model.Mood
import com.adam.insightjournal.insights.DayMood
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val axisDateFormat = DateTimeFormatter.ofPattern("MMM d")
private val ChartHeight = 168.dp
/** Space above the top grid line and below the bottom one, so the end faces and dots aren't clipped. */
private val ChartInset = 12.dp

/**
 * Mood over time: one dot per day that has a rated entry, joined by a line.
 * The horizontal position is the calendar day, so gaps in writing show as gaps.
 */
@Composable
fun MoodLineChart(days: List<DayMood>, from: LocalDate, to: LocalDate, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val dotCenter = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = "Mood from ${from.format(axisDateFormat)} to ${to.format(axisDateFormat)}, " +
        "${days.size} days with a mood"

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(ChartHeight)) {
            // The faces are the y axis: one per grid line, best mood on top.
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxHeight().width(28.dp),
            ) {
                Mood.all.reversed().forEach { Text(Mood.emoji(it), fontSize = 14.sp, lineHeight = 24.sp) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }) {
                val inset = ChartInset.toPx()
                val plotHeight = size.height - 2 * inset
                val plotWidth = size.width - 2 * inset
                val lastDay = (to.toEpochDay() - from.toEpochDay()).coerceAtLeast(1)

                fun y(mood: Double) = inset + ((Mood.MAX - mood) / (Mood.MAX - Mood.MIN)).toFloat() * plotHeight
                fun x(date: LocalDate) = inset + (date.toEpochDay() - from.toEpochDay()).toFloat() / lastDay * plotWidth

                Mood.all.forEach { mood ->
                    val gy = y(mood.toDouble())
                    drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
                }
                if (days.isEmpty()) return@Canvas

                val points = days.map { Offset(x(it.date), y(it.average)) }
                if (points.size > 1) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    val area = Path().apply {
                        addPath(path)
                        lineTo(points.last().x, size.height - inset)
                        lineTo(points.first().x, size.height - inset)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent)))
                    drawPath(
                        path,
                        line,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                // With many days the dots would merge into the line, so they shrink.
                val radius = if (points.size > 45) 2.dp.toPx() else 4.dp.toPx()
                points.forEach { point ->
                    drawCircle(line, radius, point)
                    if (points.size <= 45) drawCircle(dotCenter, radius * 0.45f, point)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp + ChartInset, end = ChartInset, top = 2.dp)) {
            AxisLabel(from.format(axisDateFormat), Modifier.weight(1f), TextAlign.Start)
            AxisLabel(to.format(axisDateFormat), Modifier.weight(1f), TextAlign.End)
        }
    }
}

/** Average mood for each day of the week, Monday first. Weekdays with no rated entry get a dash. */
@Composable
fun WeekdayBars(byWeekday: Map<DayOfWeek, Double>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            val average = byWeekday[day]
            val name = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription =
                        if (average == null) "$name: no mood recorded" else "$name: average ${formatMood(average)}"
                },
            ) {
                Text(
                    average?.let(::formatMood) ?: "–",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Box(Modifier.height(64.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(((average ?: 0.0) / Mood.MAX).toFloat().coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(
                                if (average == null) MaterialTheme.colorScheme.outlineVariant
                                else MaterialTheme.colorScheme.primary
                            ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                AxisLabel(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier, TextAlign.Center)
            }
        }
    }
}

/** How many entries got each mood, best first. */
@Composable
fun MoodDistribution(distribution: Map<Int, Int>, modifier: Modifier = Modifier) {
    val most = distribution.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Mood.all.reversed().forEach { mood ->
            val count = distribution[mood] ?: 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${Mood.label(mood)}: $count"
                },
            ) {
                Text(Mood.emoji(mood), modifier = Modifier.width(28.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    if (count > 0) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(count.toFloat() / most)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp),
                )
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier,
    )
}

/** "3.8": one decimal is as precise as a five-point scale deserves. */
fun formatMood(average: Double): String = String.format(Locale.getDefault(), "%.1f", average)

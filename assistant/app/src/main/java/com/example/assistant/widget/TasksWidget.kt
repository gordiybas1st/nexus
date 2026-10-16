package com.example.assistant.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.assistant.MainActivity
import com.example.assistant.R
import com.example.assistant.di.AppEntryPoint
import com.example.assistant.domain.model.Priority
import com.example.assistant.domain.model.Task
import com.example.assistant.notifications.NotificationHelper
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget shows. */
data class WidgetData(
    val tasks: List<Task>,
    val doneToday: Int,
    val totalToday: Int,
)

/**
 * Home-screen widget (2x2 and 4x2): today's top 3 open tasks, a progress ring for the day
 * and a "+" button that opens the task editor.
 */
class TasksWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadData(context)
        provideContent {
            GlanceTheme {
                WidgetContent(data)
            }
        }
    }

    private suspend fun loadData(context: Context): WidgetData {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, AppEntryPoint::class.java)
        val all = deps.taskRepository().observeTasks().first()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val dueToday = all.filter { it.deadline != null && it.deadline in start until end }
        val doneToday = all.count { it.isDone && (it.completedAt ?: 0L) >= start }
        val openToday = dueToday.count { !it.isDone }
        // Today's and overdue first, then other important tasks.
        val top = all.filter { !it.isDone }
            .sortedWith(
                compareBy<Task> { (it.deadline ?: Long.MAX_VALUE) >= end }
                    .thenByDescending { it.priority.ordinal }
                    .thenBy { it.deadline ?: Long.MAX_VALUE },
            )
            .take(3)
        return WidgetData(top, doneToday, doneToday + openToday)
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
    }
}

@Composable
private fun WidgetContent(data: WidgetData) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= TasksWidget.WIDE.width
    // The ring is a bitmap, so it uses fixed brand colors instead of theme providers.
    val primary = RING_COLOR
    val track = RING_TRACK

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(progressRing(data.doneToday, data.totalToday, primary, track)),
                contentDescription = context.getString(R.string.widget_progress, data.doneToday, data.totalToday),
                modifier = GlanceModifier.size(32.dp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    context.getString(R.string.widget_today),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    maxLines = 1,
                )
                Text(
                    "${data.doneToday}/${data.totalToday}",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
            Box(
                GlanceModifier
                    .size(32.dp)
                    .cornerRadius(16.dp)
                    .background(GlanceTheme.colors.primary)
                    .clickable(actionStartActivity(deepLink(context, NotificationHelper.DEEP_LINK_NEW_TASK))),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (data.tasks.isEmpty()) {
            Text(
                context.getString(R.string.widget_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        } else {
            data.tasks.forEach { task ->
                Row(
                    GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable(actionStartActivity(deepLink(context, NotificationHelper.uriForTask(task.id).toString()))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (task.priority == Priority.HIGH) "🔥 " else "• ") + task.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    val deadline = task.deadline
                    if (wide && deadline != null) {
                        Text(
                            Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault()).format(TIME),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val RING_COLOR = 0xFF6366F1.toInt()
private const val RING_TRACK = 0x406366F1

private fun deepLink(context: Context, uri: String): Intent =
    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** Draws a donut-style progress ring (Glance has no Canvas). */
private fun progressRing(done: Int, total: Int, color: Int, track: Int): Bitmap {
    val size = 96
    val stroke = 12f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = track
    canvas.drawArc(rect, 0f, 360f, false, paint)
    if (total > 0 && done > 0) {
        paint.color = color
        canvas.drawArc(rect, -90f, 360f * done / total, false, paint)
    }
    return bitmap
}

/** Widget provider registered in the manifest. */
class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksWidget()
}

/** Refreshes all widgets after data changes (debounced). */
@OptIn(FlowPreview::class)
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch {
            requests.debounce(DEBOUNCE_MS).collect {
                runCatching { TasksWidget().updateAll(context) }
            }
        }
    }

    fun refresh() {
        requests.tryEmit(Unit)
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}

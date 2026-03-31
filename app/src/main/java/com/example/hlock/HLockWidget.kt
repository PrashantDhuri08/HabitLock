package com.example.hlock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.app.usage.UsageStatsManager
import java.util.*

class HLockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.hlock_widget)
            
            // Screen time
            val totalMins = getTotalUsageToday(context)
            val hours = totalMins / 60
            val minutes = totalMins % 60
            views.setTextViewText(R.id.widget_usage_text, "${hours}h ${minutes}m")

            // Scrolls
            val sharedPrefs = context.getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
            val reels = sharedPrefs.getInt("reels_scroll_count", 0)
            val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
            val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
            views.setTextViewText(R.id.widget_scroll_text, "${reels + shorts + tiktok}")

            // Apps used count
            val appsUsed = getAppsUsedToday(context)
            views.setTextViewText(R.id.widget_apps_text, "$appsUsed")

            // Click to open main app
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_title, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getTotalUsageToday(context: Context): Long {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)

            val events = usm.queryEvents(calendar.timeInMillis, System.currentTimeMillis())
            val startTimes = mutableMapOf<String, Long>()
            var totalTime = 0L
            val endTime = System.currentTimeMillis()

            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                        startTimes[event.packageName] = event.timeStamp
                    }
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val start = startTimes.remove(event.packageName)
                        if (start != null) {
                            totalTime += event.timeStamp - start
                        }
                    }
                }
            }
            // Add currently active
            startTimes.values.forEach { start -> totalTime += endTime - start }

            return totalTime / (1000 * 60)
        }

        private fun getAppsUsedToday(context: Context): Int {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, calendar.timeInMillis, System.currentTimeMillis())
            return stats.count { it.totalTimeInForeground > 60000 } // More than 1 min
        }
    }
}

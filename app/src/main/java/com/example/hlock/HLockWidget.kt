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
            
            val totalMins = getTotalUsageToday(context)
            val hours = totalMins / 60
            val minutes = totalMins % 60
            views.setTextViewText(R.id.widget_usage_text, "${hours}h ${minutes}m")

            val sharedPrefs = context.getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
            val reels = sharedPrefs.getInt("reels_scroll_count", 0)
            val shorts = sharedPrefs.getInt("shorts_scroll_count", 0)
            val tiktok = sharedPrefs.getInt("tiktok_scroll_count", 0)
            views.setTextViewText(R.id.widget_scroll_text, "Scrolls: ${reels + shorts + tiktok}")

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
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, calendar.timeInMillis, System.currentTimeMillis())
            return stats.sumOf { it.totalTimeInForeground } / (1000 * 60)
        }
    }
}

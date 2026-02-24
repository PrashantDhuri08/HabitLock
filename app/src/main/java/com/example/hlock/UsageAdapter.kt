package com.example.hlock

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class UsageAdapter(
    private var usageList: List<AppUsageInfo>,
    private val onSetLimitClick: (AppUsageInfo) -> Unit
) : RecyclerView.Adapter<UsageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.appIcon)
        val appName: TextView = view.findViewById(R.id.appName)
        val appTime: TextView = view.findViewById(R.id.appTime)
        val appProgress: ProgressBar = view.findViewById(R.id.appUsageProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usage, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = usageList[position]
        holder.appName.text = item.appName
        
        val limitText = if (item.limitMinutes > 0) {
            val limitHours = item.limitMinutes / 60
            val limitMins = item.limitMinutes % 60
            val limitStr = if (limitHours > 0) "${limitHours}h ${limitMins}m" else "${limitMins}m"
            "${item.usageTime} / $limitStr"
        } else {
            item.usageTime
        }
        holder.appTime.text = limitText
        
        holder.appIcon.setImageDrawable(item.appIcon)
        
        val currentUsage = parseTimeToMinutes(item.usageTime).toFloat()
        val limit = if (item.limitMinutes > 0) item.limitMinutes.toFloat() else 480f // Default max if no limit
        
        holder.appProgress.progress = ((currentUsage / limit) * 100).toInt().coerceAtMost(100)

        holder.itemView.setOnClickListener { onSetLimitClick(item) }
    }

    private fun parseTimeToMinutes(timeStr: String): Int {
        return try {
            val hours = if (timeStr.contains("h")) timeStr.substringBefore("h").trim().toInt() else 0
            val minutes = if (timeStr.contains("m")) {
                timeStr.substringAfter("h", timeStr).substringBefore("m").trim().toInt()
            } else 0
            (hours * 60) + minutes
        } catch (e: Exception) {
            0
        }
    }

    override fun getItemCount() = usageList.size

    fun updateData(newList: List<AppUsageInfo>) {
        usageList = newList
        notifyDataSetChanged()
    }
}
